package com.atlas.account;

import static org.assertj.core.api.Assertions.assertThat;

import com.atlas.account.dto.request.LoginRequest;
import com.atlas.account.dto.request.MfaChallengeRequest;
import com.atlas.account.repository.AccountRepository;
import com.atlas.account.service.AccountService;
import com.atlas.application.SingleConnectionPool;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * 로그인은 DB 연결을 하나만 필요로 해야 한다.
 *
 * <p>이전 구현은 로그인 전체를 transaction으로 묶고 그 안에서 독립 transaction으로 시도 횟수를 저장해,
 * 요청 하나가 연결 2개를 동시에 요구했다. 동시 로그인이 풀 크기를 넘으면 모든 요청이 연결 1개를 쥔 채
 * 두 번째를 기다리다 실패했다(10개 풀에서 초당 50건 안팎에서 붕괴). 풀 크기 1에서 로그인이 성공하면 어떤
 * 동시성에서도 이 경로가 연결을 중첩해서 잡지 않는다는 뜻이다.
 */
@Testcontainers
@SpringBootTest
class LoginConnectionPoolIntegrationTest {
    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18.6");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.datasource.hikari.connection-timeout", () -> "3000");
        registry.add("atlas.admission.expiry-poll-interval", () -> "PT24H");
        registry.add("atlas.orders.expiry-poll-interval", () -> "PT24H");
        registry.add("atlas.payment.recovery.worker.enabled", () -> "false");
        registry.add("atlas.payment.paypal.webhook-retry.worker.enabled", () -> "false");
    }

    @Autowired DataSource dataSource;
    @Autowired AccountRepository accounts;
    @Autowired AccountService accountService;
    @Autowired PasswordEncoder passwordEncoder;

    @Test
    void loginSucceedsWithASingleConnectionPool() throws Exception {
        // 시작 단계(Flyway, Hibernate)는 연결이 둘 이상 필요하므로 기동한 뒤에 풀을 1개로 줄인다.
        SingleConnectionPool.shrink(dataSource);

        String email = "pool1-" + UUID.randomUUID() + "@example.com";
        String password = "long-enough-password-123";
        UUID accountId = accounts.createAccount(email, passwordEncoder.encode(password)).orElseThrow();
        accounts.createBuyer(accountId);
        accounts.verifyEmail(accountId, Instant.now());

        var response = accountService.login(new LoginRequest(email, password), "198.51.100.7");

        assertThat(response.tokens()).isNotNull();
    }

    @Test
    void mfaChallengeCompletionSucceedsWithASingleConnectionPool() throws Exception {
        SingleConnectionPool.shrink(dataSource);

        String email = "pool1-mfa-" + UUID.randomUUID() + "@example.com";
        String password = "long-enough-password-123";
        UUID accountId = accounts.createAccount(email, passwordEncoder.encode(password)).orElseThrow();
        accounts.createBuyer(accountId);
        accounts.verifyEmail(accountId, Instant.now());
        Instant now = Instant.now();
        accounts.savePendingMfaSecret(accountId, "unused-for-recovery-codes", now);
        // MFA 활성화는 TOTP 단계 사용 기록이 있어야 하므로 확인 절차를 거친 것처럼 기록한다.
        assertThat(accounts.markMfaStepUsed(accountId, 1L)).isTrue();
        assertThat(accounts.enableMfa(accountId, now)).isTrue();
        String recoveryCode = "recovery-" + UUID.randomUUID();
        accounts.replaceMfaRecoveryCodes(accountId, List.of(sha256Hex(recoveryCode)), now);
        String challenge = accountService.login(new LoginRequest(email, password), "198.51.100.8").challengeToken();

        var tokens = accountService.completeMfaLogin(new MfaChallengeRequest(challenge, null, recoveryCode));

        assertThat(tokens.accessToken()).isNotBlank();
    }

    private static String sha256Hex(String value) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8)));
    }
}
