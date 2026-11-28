package com.atlas.account;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.atlas.account.dto.response.AccountResponse;
import com.atlas.account.dto.request.AccountRequest;
import com.atlas.account.repository.AccountRepository;
import com.atlas.account.service.AccountService;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * 운영자 계정 정지 API 의 계약을 고정합니다.
 *
 * <p>강조하는 것: 일반 사용자 토큰으로는 접근할 수 없어야 하고, 정지 직후 대상
 * 계정의 세션이 즉시 끊겨야 하며, 같은 idempotency-key 재시도가 감사 기록을
 * 중복시키지 않아야 합니다.
 */
@Testcontainers
@SpringBootTest
@AutoConfigureMockMvc
class AccountSuspensionOperatorIntegrationTest {
    private static final String OPERATOR = "operator";
    private static final String OPERATOR_PASSWORD = "operator-password-at-least-32-chars-long";

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18.6")
            .withDatabaseName("atlas")
            .withUsername("atlas")
            .withPassword("atlas");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("atlas.admission.expiry-poll-interval", () -> "PT24H");
        registry.add("atlas.orders.expiry-poll-interval", () -> "PT24H");
        registry.add("atlas.account.suspension.operator.enabled", () -> "true");
        registry.add("atlas.account.suspension.operator.username", () -> OPERATOR);
        registry.add("atlas.account.suspension.operator.password", () -> OPERATOR_PASSWORD);
    }

    @Autowired private MockMvc mockMvc;
    @Autowired private AccountService accountService;
    @Autowired private AccountRepository accounts;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private JdbcTemplate jdbc;

    private UUID newVerifiedAccount(String password) {
        UUID accountId = accounts.createAccount("suspend-" + UUID.randomUUID() + "@example.com",
                passwordEncoder.encode(password)).orElseThrow();
        accounts.createBuyer(accountId);
        accounts.verifyEmail(accountId, Instant.now());
        return accountId;
    }

    @Test
    void suspensionCutsSessionsImmediatelyAndIsAudited() throws Exception {
        String password = "long-enough-password-123";
        UUID accountId = newVerifiedAccount(password);
        AccountResponse.Login session = accountService.login(new AccountRequest.Login(
                jdbc.queryForObject("SELECT email FROM user_account WHERE account_id = ?", String.class, accountId),
                password), "198.51.100.7");

        // 일반 사용자 토큰으로는 운영자 API 에 접근할 수 없다.
        mockMvc.perform(post("/api/internal/accounts/{id}/suspension", accountId)
                        .header("Authorization", "Bearer " + session.tokens().accessToken())
                        .header("Idempotency-Key", "user-token-attempt")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"attempt by user token\"}"))
                .andExpect(status().isUnauthorized());

        // 같은 운영자 체인이 정지 Seller 주문 환불 경로도 지킨다(ADR-0112). 계정에 연결되지 않은 Seller는 404다.
        String sellerRefund = "/api/internal/accounts/{id}/sellers/{sellerId}/orders/{orderId}/refund";
        mockMvc.perform(post(sellerRefund, accountId, UUID.randomUUID(), UUID.randomUUID())
                        .header("Authorization", "Bearer " + session.tokens().accessToken())
                        .header("Idempotency-Key", "user-token-refund"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post(sellerRefund, accountId, UUID.randomUUID(), UUID.randomUUID())
                        .with(httpBasic(OPERATOR, OPERATOR_PASSWORD))
                        .header("Idempotency-Key", "unlinked-seller-refund"))
                .andExpect(status().isNotFound());

        // 사유가 비어 있으면 거부된다.
        mockMvc.perform(post("/api/internal/accounts/{id}/suspension", accountId)
                        .with(httpBasic(OPERATOR, OPERATOR_PASSWORD))
                        .header("Idempotency-Key", "blank-reason")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"   \"}"))
                .andExpect(status().isBadRequest());

        // 정상 정지.
        mockMvc.perform(post("/api/internal/accounts/{id}/suspension", accountId)
                        .with(httpBasic(OPERATOR, OPERATOR_PASSWORD))
                        .header("Idempotency-Key", "suspend-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"fraud report #42\"}"))
                .andExpect(status().isNoContent());

        // 대상 계정의 세션이 즉시 끊긴다.
        mockMvc.perform(get("/api/auth/sessions")
                        .header("Authorization", "Bearer " + session.tokens().accessToken()))
                .andExpect(status().isUnauthorized());

        // 감사 기록이 남는다.
        mockMvc.perform(get("/api/internal/accounts/{id}/suspension/history", accountId)
                        .with(httpBasic(OPERATOR, OPERATOR_PASSWORD)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].action").value("SUSPEND"))
                .andExpect(jsonPath("$[0].reason").value("fraud report #42"))
                .andExpect(jsonPath("$[0].actor").isNotEmpty());

        // 같은 키로 다시 요청해도 감사 기록이 늘지 않는다.
        mockMvc.perform(post("/api/internal/accounts/{id}/suspension", accountId)
                        .with(httpBasic(OPERATOR, OPERATOR_PASSWORD))
                        .header("Idempotency-Key", "suspend-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"fraud report #42\"}"))
                .andExpect(status().isNoContent());
        Integer recorded = jdbc.queryForObject(
                "SELECT count(*) FROM account_suspension_action WHERE account_id = ?", Integer.class, accountId);
        assertThat(recorded).isEqualTo(1);

        // 해제하면 다시 로그인할 수 있다.
        mockMvc.perform(delete("/api/internal/accounts/{id}/suspension", accountId)
                        .with(httpBasic(OPERATOR, OPERATOR_PASSWORD))
                        .header("Idempotency-Key", "release-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"false positive\"}"))
                .andExpect(status().isNoContent());
        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + jdbc.queryForObject(
                                "SELECT email FROM user_account WHERE account_id = ?", String.class, accountId)
                                + "\",\"password\":\"" + password + "\"}"))
                .andExpect(status().isOk());
    }
}
