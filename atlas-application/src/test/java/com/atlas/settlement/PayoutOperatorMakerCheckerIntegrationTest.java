package com.atlas.settlement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.atlas.account.dto.request.AccountRequest;
import com.atlas.account.repository.AccountRepository;
import com.atlas.account.service.AccountService;
import com.atlas.application.dto.request.SettlementHttpRequest;
import com.atlas.application.service.payout.PayoutDispatchApprovalService;
import com.atlas.application.service.payout.SellerPayoutProfileService;
import com.atlas.payment.PayPalIntegrationTestSupport;
import com.atlas.settlement.dto.request.SettlementPostingRequest;
import com.atlas.settlement.dto.response.PayoutResponse;
import com.atlas.settlement.service.SettlementPayoutService;
import com.atlas.settlement.service.SettlementService;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.json.JsonMapper;

/**
 * 사람별 운영자 신원과 "프로필 검증자 ≠ 지급 승인자" 규칙을 실제 PostgreSQL과 HTTP 경계로 확인한다(ADR-0092, EXP-096).
 *
 * <p>운영자는 일반 account 로그인으로 받은 JWT로 인증한다. 규칙은 감사 행이 저장되는 순간 DB trigger가 검사하므로
 * 이 테스트는 service를 거치지 않는 직접 INSERT도 함께 확인한다. 음성 대조로 trigger를 끄면 같은 사람의 흐름이 성공해
 * 이 테스트가 위반을 탐지할 수 있음을 보인다. 두 사람의 동시 승인은 {@code H7_ROUNDS} 환경 변수만큼 반복한다(기본 3).
 */
@Testcontainers
@SpringBootTest
@AutoConfigureMockMvc
class PayoutOperatorMakerCheckerIntegrationTest extends PayPalIntegrationTestSupport {
    private static final String SEPARATION_TRIGGER = "settlement_payout_operator_action_separation";
    private static final String PASSWORD = "long-enough-password-123";

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
        paypalTestProperties(registry);
        registry.add("atlas.admission.expiry-poll-interval", () -> "PT24H");
        registry.add("atlas.orders.expiry-poll-interval", () -> "PT24H");
        registry.add("atlas.payment.recovery.worker.enabled", () -> "false");
        registry.add("atlas.payment.paypal.payouts.enabled", () -> "true");
        registry.add("atlas.payment.paypal.payouts.recipient-encryption-key",
                () -> Base64.getEncoder().encodeToString(new byte[32]));
        registry.add("atlas.settlement.payout.operator.enabled", () -> "true");
    }

    @Autowired private MockMvc mockMvc;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private AccountService accountService;
    @Autowired private AccountRepository accounts;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private SettlementService settlement;
    @Autowired private SettlementPayoutService payouts;
    @Autowired private SellerPayoutProfileService profiles;
    @Autowired private PayoutDispatchApprovalService approvals;

    private final AtomicInteger origins = new AtomicInteger();

    @Test
    void theSameOperatorCannotApproveThePayoutOfAProfileItVerified() throws Exception {
        Operator verifier = operator();
        Operator approver = operator();
        Seeded seeded = seed();

        assertThat(verify(verifier, seeded).getStatus()).isEqualTo(200);

        MockHttpServletResponse sameOperator = approve(verifier, seeded, "same-" + UUID.randomUUID());
        assertThat(sameOperator.getStatus()).isEqualTo(403);
        assertThat(code(sameOperator)).isEqualTo("SEPARATION_OF_DUTIES");
        assertThat(actions(seeded, "PAYOUT_APPROVED")).as("거절된 승인은 어떤 행도 남기지 않는다").isZero();
        assertThat(outboxRows(seeded)).isZero();
        assertThat(payouts.find(seeded.payoutId()).state().name()).isEqualTo("REQUESTED");

        // 요청 본문에 다른 운영자를 actor로 적어도 actor는 인증된 principal이다.
        MockHttpServletResponse spoofed = postAs(verifier, "/api/internal/settlement/payouts/" + seeded.payoutId()
                + "/approve", "spoof-" + UUID.randomUUID(),
                "{\"reason\":\"reviewed\",\"actor\":\"" + approver.accountId() + "\"}");
        assertThat(spoofed.getStatus()).isEqualTo(403);

        String key = "approve-" + UUID.randomUUID();
        assertThat(approve(approver, seeded, key).getStatus()).isEqualTo(202);
        assertThat(approve(approver, seeded, key).getStatus()).as("같은 key 재시도는 같은 결과").isEqualTo(202);
        assertThat(actions(seeded, "PAYOUT_APPROVED")).isEqualTo(1);
        assertThat(actionActors(seeded, "PAYOUT_APPROVED")).containsExactly(approver.accountId().toString());
        assertThat(jdbc.queryForObject("SELECT verified_by FROM seller_payout_profile WHERE seller_id = ?",
                String.class, seeded.sellerId())).isEqualTo(verifier.accountId().toString());

        // 이미 승인된 지급을 검증자가 다른 key로 다시 승인해도 거절된다.
        assertThat(approve(verifier, seeded, "replay-" + UUID.randomUUID()).getStatus()).isEqualTo(403);
        // 서비스를 거치지 않는 직접 INSERT도 DB가 거절한다.
        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO settlement_payout_operator_action(payout_id, idempotency_key, action, actor, reason, created_at)
                VALUES (?, ?, 'PAYOUT_APPROVAL_REPLAYED', ?, 'direct', now())
                """, seeded.payoutId(), "direct-" + UUID.randomUUID(), verifier.accountId().toString()))
                .isInstanceOf(DataIntegrityViolationException.class);
        // 한 지급의 승인 행은 DB가 한 건으로 제한한다.
        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO settlement_payout_operator_action(payout_id, idempotency_key, action, actor, reason, created_at)
                VALUES (?, ?, 'PAYOUT_APPROVED', ?, 'direct', now())
                """, seeded.payoutId(), "second-" + UUID.randomUUID(), UUID.randomUUID().toString()))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("uq_settlement_payout_operator_action_approved");
    }

    @Test
    void anOperatorCannotVerifyThePayoutProfileOfItsOwnAccount() throws Exception {
        Operator owner = operator();
        Operator other = operator();
        Seeded seeded = seed(owner.accountId());

        MockHttpServletResponse own = verify(owner, seeded);
        assertThat(own.getStatus()).isEqualTo(403);
        assertThat(code(own)).isEqualTo("SEPARATION_OF_DUTIES");
        assertThat(jdbc.queryForObject("SELECT status FROM seller_payout_profile WHERE seller_id = ?", String.class,
                seeded.sellerId())).as("거절되면 프로필 상태 전이도 되돌려진다").isEqualTo("PENDING_REVIEW");
        assertThat(verify(other, seeded).getStatus()).isEqualTo(200);
    }

    @Test
    void theRuleComparesWithTheVerifierOfTheProfileVersionBoundToTheOutbox() {
        // 지급 outbox는 프로필 v2에 묶였고, 프로필은 그 뒤 v4로 넘어가 다른 사람이 다시 검증했다. 현재 행의 검증자(B)가 아니라
        // 묶인 버전의 검증자(A)만 거절되어야 한다.
        String verifierOfBoundVersion = UUID.randomUUID().toString();
        String verifierOfCurrentVersion = UUID.randomUUID().toString();
        Seeded seeded = seed();
        jdbc.update("""
                INSERT INTO seller_payout_profile_action(seller_id, profile_version, action, idempotency_key,
                    request_facts_sha256, actor, reason, created_at)
                VALUES (?, 2, 'PROFILE_VERIFIED', ?, repeat('0', 64), ?, 'case', now()),
                       (?, 4, 'PROFILE_VERIFIED', ?, repeat('0', 64), ?, 'case', now())
                """, seeded.sellerId(), "v2-" + seeded.sellerId(), verifierOfBoundVersion,
                seeded.sellerId(), "v4-" + seeded.sellerId(), verifierOfCurrentVersion);
        jdbc.update("UPDATE seller_payout_profile SET profile_version = 4, status = 'ACTIVE', verified_at = now(), "
                + "verified_by = ? WHERE seller_id = ?", verifierOfCurrentVersion, seeded.sellerId());
        jdbc.update("""
                INSERT INTO settlement_payout_dispatch_outbox(payout_id, profile_version, recipient_email_ciphertext,
                    recipient_country_code, requested_amount_won, provider_amount_minor, fx_rate_won_per_usd, fx_source,
                    fx_as_of_date, provider_debit_book_value_won, payout_fx_rounding_expense_won, sender_batch_id,
                    sender_item_id, paypal_request_id, request_payload_sha256, status, available_at, first_enqueued_at,
                    created_at, updated_at)
                VALUES (?, 2, 'x', 'US', 1000, 100, 1000, 'test', current_date, 1000, 0, ?, ?, ?, repeat('a', 64),
                    'PENDING', now(), now(), now(), now())
                """, seeded.payoutId(), "b-" + seeded.payoutId(), "i-" + seeded.payoutId(), "r-" + seeded.payoutId());
        String insert = """
                INSERT INTO settlement_payout_operator_action(payout_id, idempotency_key, action, actor, reason, created_at)
                VALUES (?, ?, 'PAYOUT_APPROVAL_REPLAYED', ?, 'direct', now())
                """;

        assertThatThrownBy(() -> jdbc.update(insert, seeded.payoutId(), "a-" + UUID.randomUUID(), verifierOfBoundVersion))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThat(jdbc.update(insert, seeded.payoutId(), "b-" + UUID.randomUUID(), verifierOfCurrentVersion)).isEqualTo(1);
    }

    @Test
    void withTheRuleDisabledTheSameOperatorFlowSucceeds() throws Exception {
        Operator operator = operator();
        Seeded seeded = seed();
        jdbc.execute("ALTER TABLE settlement_payout_operator_action DISABLE TRIGGER " + SEPARATION_TRIGGER);
        try {
            assertThat(verify(operator, seeded).getStatus()).isEqualTo(200);
            assertThat(approve(operator, seeded, "negative-" + UUID.randomUUID()).getStatus())
                    .as("음성 대조: 규칙이 없으면 같은 사람이 검증과 승인을 모두 한다").isEqualTo(202);
        } finally {
            jdbc.execute("ALTER TABLE settlement_payout_operator_action ENABLE TRIGGER " + SEPARATION_TRIGGER);
        }
        assertThat(actionActors(seeded, "PAYOUT_APPROVED")).containsExactly(operator.accountId().toString());
    }

    @Test
    void actorsThatAreNotPersonAccountsAreNotSubjectToTheRule() {
        // rollback flag(identity=shared-credential)의 actor는 설정된 username이다. 사람 신원이 아니라 규칙이 적용되지 않는다.
        Seeded seeded = seed();
        profiles.verify(seeded.sellerId(), "shared-operator", "legacy-verify-" + UUID.randomUUID(),
                new SettlementHttpRequest.VerifySellerPayoutProfile(1, "case-1"));
        approvals.approve(seeded.payoutId(), "legacy-approve-" + UUID.randomUUID(), "shared-operator", "reviewed");
        assertThat(actionActors(seeded, "PAYOUT_APPROVED")).containsExactly("shared-operator");
    }

    @Test
    void concurrentApprovalsByTwoOperatorsLeaveExactlyOneApprovalAndTheVerifierIsAlwaysRejected() throws Exception {
        int rounds = Integer.parseInt(System.getenv().getOrDefault("H7_ROUNDS", "3"));
        Operator verifier = operator();
        Operator first = operator();
        Operator second = operator();
        int approvedRows = 0;
        int outboxRows = 0;
        int verifierRejected = 0;
        int approverConflicts = 0;
        ExecutorService executor = Executors.newFixedThreadPool(3);
        try {
            for (int round = 0; round < rounds; round++) {
                Seeded seeded = seed();
                assertThat(verify(verifier, seeded).getStatus()).isEqualTo(200);
                CyclicBarrier start = new CyclicBarrier(3);
                Future<MockHttpServletResponse> byVerifier = executor.submit(() -> {
                    start.await();
                    return approve(verifier, seeded, "v-" + UUID.randomUUID());
                });
                Future<MockHttpServletResponse> byFirst = executor.submit(() -> {
                    start.await();
                    return approve(first, seeded, "a-" + UUID.randomUUID());
                });
                Future<MockHttpServletResponse> bySecond = executor.submit(() -> {
                    start.await();
                    return approve(second, seeded, "b-" + UUID.randomUUID());
                });
                MockHttpServletResponse rejected = byVerifier.get();
                MockHttpServletResponse one = byFirst.get();
                MockHttpServletResponse two = bySecond.get();

                assertThat(rejected.getStatus()).as("검증자의 승인은 언제나 거절").isEqualTo(403);
                verifierRejected++;
                assertThat(List.of(one.getStatus(), two.getStatus())).as("승인자 둘의 응답")
                        .allMatch(status -> status == 202 || status == 409).contains(202);
                approverConflicts += (one.getStatus() == 409 ? 1 : 0) + (two.getStatus() == 409 ? 1 : 0);
                assertThat(actions(seeded, "PAYOUT_APPROVED")).as("승인 행은 정확히 한 건").isEqualTo(1);
                assertThat(outboxRows(seeded)).as("outbox는 정확히 한 행").isEqualTo(1);
                approvedRows += actions(seeded, "PAYOUT_APPROVED");
                outboxRows += outboxRows(seeded);
            }
        } finally {
            executor.shutdownNow();
        }
        System.out.println("H7-CONCURRENCY rounds=" + rounds + " approvedRows=" + approvedRows
                + " outboxRows=" + outboxRows + " verifierRejected=" + verifierRejected
                + " approverConflict409=" + approverConflicts + " approverAccepted202=" + (2 * rounds - approverConflicts));
    }

    @Test
    void manualPayoutStepsRequireDifferentOperators() throws Exception {
        Operator approver = operator();
        Operator sender = operator();
        Seeded seeded = seed();
        String base = "/api/internal/settlement/payouts/" + seeded.payoutId();
        String evidence = "{\"evidenceReference\":\"bank-ref-1\"}";
        String reason = "{\"reason\":\"reviewed\"}";

        assertThat(postAs(approver, base + "/manual-approve", "m-approve", reason).getStatus()).isEqualTo(202);
        MockHttpServletResponse sentByApprover = postAs(approver, base + "/manual-sent", "m-sent-a", evidence);
        assertThat(sentByApprover.getStatus()).isEqualTo(403);
        assertThat(code(sentByApprover)).isEqualTo("SEPARATION_OF_DUTIES");
        assertThat(payouts.find(seeded.payoutId()).state().name()).as("거절되면 상태 전이도 되돌려진다").isEqualTo("APPROVED");
        assertThat(postAs(sender, base + "/manual-sent", "m-sent-b", evidence).getStatus()).isEqualTo(200);
        assertThat(postAs(sender, base + "/manual-confirm", "m-confirm-b", evidence).getStatus()).isEqualTo(403);
        assertThat(postAs(approver, base + "/manual-confirm", "m-confirm-a", evidence).getStatus()).isEqualTo(200);
    }

    @Test
    void onlyAnActiveMfaEnrolledOperatorAccountPassesTheOperatorBoundary() throws Exception {
        String path = "/api/internal/settlement/payouts/needs-review";
        assertThat(mockMvc.perform(get(path)).andReturn().getResponse().getStatus()).isEqualTo(401);

        Operator operator = operator();
        assertThat(status(operator, path)).isEqualTo(200);

        Session buyer = session(newAccount());
        assertThat(status(buyer.token(), path)).as("운영자가 아닌 일반 계정 토큰").isEqualTo(403);

        UUID withoutMfa = newAccount();
        Session noMfa = session(withoutMfa);
        grant(withoutMfa);
        assertThat(status(noMfa.token(), path)).as("MFA를 등록하지 않은 운영자").isEqualTo(403);

        jdbc.update("UPDATE payout_operator_account SET revoked_at = now() WHERE account_id = ?", operator.accountId());
        assertThat(status(operator, path)).as("권한 해제는 같은 토큰에 바로 적용").isEqualTo(403);

        Operator suspended = operator();
        jdbc.update("UPDATE user_account SET status = 'DISABLED' WHERE account_id = ?", suspended.accountId());
        assertThat(status(suspended, path)).as("정지된 계정").isEqualTo(401);
    }

    private MockHttpServletResponse verify(Operator operator, Seeded seeded) throws Exception {
        return postAs(operator, "/api/internal/settlement/payout-profiles/" + seeded.sellerId() + "/verify",
                "verify-" + UUID.randomUUID(), "{\"profileVersion\":1,\"evidenceReference\":\"case-1\"}");
    }

    private MockHttpServletResponse approve(Operator operator, Seeded seeded, String key) throws Exception {
        return postAs(operator, "/api/internal/settlement/payouts/" + seeded.payoutId() + "/approve", key,
                "{\"reason\":\"reviewed\"}");
    }

    private MockHttpServletResponse postAs(Operator operator, String path, String key, String body) throws Exception {
        MockHttpServletRequestBuilder request = post(path)
                .header("Authorization", "Bearer " + operator.token())
                .header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body);
        return mockMvc.perform(request).andReturn().getResponse();
    }

    private int status(Operator operator, String path) throws Exception {
        return status(operator.token(), path);
    }

    private int status(String token, String path) throws Exception {
        return mockMvc.perform(get(path).header("Authorization", "Bearer " + token)).andReturn().getResponse()
                .getStatus();
    }

    private String code(MockHttpServletResponse response) throws Exception {
        return JsonMapper.builder().build().readTree(response.getContentAsString()).path("code").asString();
    }

    private int actions(Seeded seeded, String action) {
        return jdbc.queryForObject("SELECT count(*) FROM settlement_payout_operator_action WHERE payout_id = ? AND action = ?",
                Integer.class, seeded.payoutId(), action);
    }

    private List<String> actionActors(Seeded seeded, String action) {
        return jdbc.queryForList("SELECT actor FROM settlement_payout_operator_action WHERE payout_id = ? AND action = ?",
                String.class, seeded.payoutId(), action);
    }

    private int outboxRows(Seeded seeded) {
        return jdbc.queryForObject("SELECT count(*) FROM settlement_payout_dispatch_outbox WHERE payout_id = ?",
                Integer.class, seeded.payoutId());
    }

    private UUID newAccount() {
        UUID accountId = accounts.createAccount("operator-" + UUID.randomUUID() + "@example.test",
                passwordEncoder.encode(PASSWORD)).orElseThrow();
        accounts.createBuyer(accountId);
        accounts.verifyEmail(accountId, Instant.now());
        return accountId;
    }

    private Session session(UUID accountId) {
        String email = jdbc.queryForObject("SELECT email FROM user_account WHERE account_id = ?", String.class, accountId);
        String origin = "198.51.100." + (origins.incrementAndGet() % 250 + 1);
        return new Session(accountId, accountService.login(new AccountRequest.Login(email, PASSWORD), origin)
                .tokens().accessToken());
    }

    private void grant(UUID accountId) {
        jdbc.update("INSERT INTO payout_operator_account(account_id, granted_by) VALUES (?, 'integration-test')", accountId);
    }

    /** 로그인은 TOTP 등록 전에 한다. 등록 상태는 권한 확인이 요청마다 읽으며, TOTP 로그인 흐름은 account 테스트가 다룬다. */
    private Operator operator() {
        UUID accountId = newAccount();
        Session session = session(accountId);
        grant(accountId);
        jdbc.update("INSERT INTO account_mfa_secret(account_id, encrypted_secret, enabled_at) VALUES (?, 'test', now())",
                accountId);
        return new Operator(accountId, session.token());
    }

    private Seeded seed() {
        UUID recipientAccountId = UUID.randomUUID();
        jdbc.update("INSERT INTO user_account(account_id, email, password_hash, email_verified_at) VALUES (?, ?, ?, now())",
                recipientAccountId, "seller-" + UUID.randomUUID() + "@example.test", "hash");
        return seed(recipientAccountId);
    }

    private Seeded seed(UUID recipientAccountId) {
        UUID sellerId = UUID.randomUUID();
        jdbc.update("INSERT INTO seller(seller_id) VALUES (?)", sellerId);
        jdbc.update("INSERT INTO seller_account(account_id, seller_id) VALUES (?, ?)", recipientAccountId, sellerId);
        jdbc.update("""
                INSERT INTO seller_payout_profile(seller_id, recipient_account_id, country_code, payout_currency,
                    status, profile_version, created_at, updated_at)
                VALUES (?, ?, 'US', 'USD', 'PENDING_REVIEW', 1, now(), now())
                """, sellerId, recipientAccountId);
        settlement.recordCapture(new SettlementPostingRequest.Capture(UUID.randomUUID(), UUID.randomUUID(), sellerId, 10_000));
        Instant now = Instant.now();
        PayoutResponse.SellerPayout payout = payouts.request(sellerId, now.minus(Duration.ofDays(1)),
                now.plus(Duration.ofDays(1)), "h7-" + UUID.randomUUID());
        return new Seeded(sellerId, payout.payoutId());
    }

    private record Session(UUID accountId, String token) {}

    private record Operator(UUID accountId, String token) {}

    private record Seeded(UUID sellerId, UUID payoutId) {}
}
