package com.atlas.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.atlas.application.dto.response.PaymentRecoveryRequeueResponse;
import com.atlas.application.service.PaymentRecoveryOperatorService;
import com.atlas.payment.persistence.state.PaymentRecoveryJobStatus;
import com.atlas.payment.dto.response.PaymentRecoveryJob;
import com.atlas.payment.persistence.state.PaymentRecoveryOperationType;
import com.atlas.payment.service.PaymentRecoveryService;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.springframework.test.web.servlet.MockMvc;

@Testcontainers
@SpringBootTest
@AutoConfigureMockMvc
class PaymentRecoveryIntegrationTest {
    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18.6");

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private PaymentRecoveryService recovery;

    @Autowired
    private PaymentRecoveryOperatorService operator;
    @Autowired
    private MockMvc mockMvc;

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry properties) {
        properties.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        properties.add("spring.datasource.username", POSTGRES::getUsername);
        properties.add("spring.datasource.password", POSTGRES::getPassword);
        properties.add("atlas.admission.expiry-poll-interval", () -> "PT24H");
        properties.add("atlas.orders.expiry-poll-interval", () -> "PT24H");
        properties.add("atlas.paypal.webhook-retry.worker.enabled", () -> "false");
        properties.add("atlas.payment.recovery.worker.enabled", () -> "false");
        properties.add("atlas.payment.recovery.operator.enabled", () -> "true");
        properties.add("atlas.payment.recovery.operator.username", () -> "recovery-operator");
        properties.add("atlas.payment.recovery.operator.password",
                () -> "local-test-operator-secret-at-least-32-characters");
    }

    @BeforeEach
    void clearRecoveryFixtures() {
        jdbc.update("DELETE FROM payment_recovery_operator_action");
        jdbc.update("DELETE FROM payment_recovery_job");
        jdbc.update("DELETE FROM payment_refund_operation");
        jdbc.update("DELETE FROM payment_attempt");
    }

    @Test
    void operatorReviewEndpointRequiresDedicatedBasicAuthentication() throws Exception {
        mockMvc.perform(get("/api/internal/payment/recovery/needs-review"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/internal/payment/recovery/needs-review")
                        .with(httpBasic("recovery-operator", "local-test-operator-secret-at-least-32-characters")))
                .andExpect(status().isOk());
    }

    @Test
    void onlyOneConcurrentWorkerClaimsAnOperationAndExpiredLeaseFencesStaleCompletion() throws Exception {
        UUID attemptId = createAttempt();
        recovery.enqueueAttempt(attemptId, Duration.ZERO);
        recovery.markAttemptForApplication(attemptId);
        UUID firstToken = UUID.randomUUID();
        UUID secondToken = UUID.randomUUID();
        CountDownLatch start = new CountDownLatch(1);

        try (var executor = Executors.newFixedThreadPool(2)) {
            Future<List<PaymentRecoveryJob>> first = executor.submit(() -> {
                start.await();
                return recovery.claimDue(1, firstToken, Duration.ofMinutes(1), 8, Duration.ofHours(24));
            });
            Future<List<PaymentRecoveryJob>> second = executor.submit(() -> {
                start.await();
                return recovery.claimDue(1, secondToken, Duration.ofMinutes(1), 8, Duration.ofHours(24));
            });
            start.countDown();
            List<PaymentRecoveryJob> firstResult = first.get();
            List<PaymentRecoveryJob> secondResult = second.get();
            List<PaymentRecoveryJob> claimed = firstResult.isEmpty() ? secondResult : firstResult;

            assertThat(firstResult.size() + secondResult.size()).isEqualTo(1);
            PaymentRecoveryJob original = claimed.getFirst();
            assertThat(original.operationType()).isEqualTo(PaymentRecoveryOperationType.PAYMENT_ATTEMPT);
            assertThat(original.operationId()).isEqualTo(attemptId);
            jdbc.update("""
                    UPDATE payment_recovery_job
                    SET lease_until = CURRENT_TIMESTAMP - INTERVAL '1 second'
                    WHERE recovery_job_id = ?
                    """, original.recoveryJobId());

            UUID reclaimedToken = UUID.randomUUID();
            PaymentRecoveryJob reclaimed = recovery.claimDue(
                    1, reclaimedToken, Duration.ofMinutes(1), 8, Duration.ofHours(24)).getFirst();
            assertThat(reclaimed.recoveryJobId()).isEqualTo(original.recoveryJobId());
            assertThat(reclaimed.leaseToken()).isEqualTo(reclaimedToken);
            assertThat(reclaimed.attemptCount()).isEqualTo(2);
            assertThat(recovery.complete(original)).isFalse();
            assertThat(recovery.complete(reclaimed)).isTrue();
        }
    }

    @Test

    void buyerCallbackInvalidatesAStaleApprovalWaitLease() {
        UUID attemptId = createAttempt();
        jdbc.update("""
                UPDATE payment_attempt
                SET state = 'AUTHORIZATION_PENDING',
                    payment_phase = 'AUTHORIZATION',
                    authorization_active = FALSE,
                    provider_order_id = 'paypal-order-callback-race',
                    approval_url = 'https://provider.test/approve'
                WHERE attempt_id = ?
                """, attemptId);
        recovery.enqueueAttempt(attemptId, Duration.ZERO);
        PaymentRecoveryJob claimed = recovery.claimDue(
                1, UUID.randomUUID(), Duration.ofMinutes(1), 8, Duration.ofHours(24)).getFirst();

        recovery.activateAttemptFromBuyer(attemptId, Duration.ZERO);

        assertThat(recovery.waitForBuyer(claimed)).isFalse();
        assertThat(jdbc.queryForObject("""
                SELECT status FROM payment_recovery_job WHERE recovery_job_id = ?
                """, String.class, claimed.recoveryJobId())).isEqualTo("PENDING");
    }

    @Test
    void staleApprovalWaitCannotHideAProgressedCaptureOperation() {
        UUID attemptId = createAttempt();
        jdbc.update("""
                UPDATE payment_attempt
                SET state = 'AUTHORIZATION_PENDING',
                    payment_phase = 'AUTHORIZATION',
                    authorization_active = FALSE,
                    provider_order_id = 'paypal-order-phase-race',
                    approval_url = 'https://provider.test/approve'
                WHERE attempt_id = ?
                """, attemptId);
        recovery.enqueueAttempt(attemptId, Duration.ZERO);
        PaymentRecoveryJob claimed = recovery.claimDue(
                1, UUID.randomUUID(), Duration.ofMinutes(1), 8, Duration.ofHours(24)).getFirst();
        jdbc.update("""
                UPDATE payment_attempt
                SET state = 'CAPTURE_PENDING', payment_phase = 'CAPTURE', authorization_active = TRUE
                WHERE attempt_id = ?
                """, attemptId);

        assertThat(recovery.waitForBuyer(claimed)).isFalse();
        assertThat(jdbc.queryForObject("""
                SELECT status FROM payment_recovery_job WHERE recovery_job_id = ?
                """, String.class, claimed.recoveryJobId())).isEqualTo("PENDING");
    }
    @Test
    void refundWorkIsDurableAndOperatorRequeueIsBoundedAndIdempotent() {
        UUID attemptId = createAttempt();
        UUID refundId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO payment_refund_operation (
                    refund_operation_id, attempt_id, capture_id, idempotency_key, amount_won,
                    currency, provider_request_id, refund_state, created_at, updated_at
                ) VALUES (?, ?, 'capture-id', 'refund-key', 100, 'KRW', ?, 'UNKNOWN',
                    CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """, refundId, attemptId, UUID.randomUUID());
        recovery.enqueueAttempt(attemptId, Duration.ZERO);
        recovery.enqueueRefund(refundId, Duration.ZERO);

        List<PaymentRecoveryJob> claimed = recovery.claimDue(
                10, UUID.randomUUID(), Duration.ofMinutes(1), 8, Duration.ofHours(24));
        assertThat(claimed).extracting(PaymentRecoveryJob::operationType)
                .containsExactlyInAnyOrder(
                        PaymentRecoveryOperationType.PAYMENT_ATTEMPT,
                        PaymentRecoveryOperationType.PAYMENT_REFUND);
        PaymentRecoveryJob exhausted = claimed.stream()
                .filter(job -> job.operationType() == PaymentRecoveryOperationType.PAYMENT_REFUND)
                .findFirst().orElseThrow();
        assertThat(recovery.reschedule(
                exhausted, Duration.ZERO, 1, Duration.ofHours(24), "MAX_ATTEMPTS")).isTrue();
        assertThat(recovery.count(PaymentRecoveryJobStatus.NEEDS_REVIEW)).isEqualTo(1);

        String idempotencyKey = "operator-requeue-1";
        PaymentRecoveryRequeueResponse accepted = operator.requeue(
                exhausted.recoveryJobId(), idempotencyKey, "ops-user", "Provider state reviewed");
        PaymentRecoveryRequeueResponse duplicate = operator.requeue(
                exhausted.recoveryJobId(), idempotencyKey, "ops-user", "Provider state reviewed");

        assertThat(accepted.status()).isEqualTo("PENDING");
        assertThat(accepted.manualRequeueCount()).isEqualTo(1);
        assertThat(accepted.duplicate()).isFalse();
        assertThat(duplicate.duplicate()).isTrue();
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM payment_recovery_operator_action WHERE recovery_job_id = ?",
                Integer.class, exhausted.recoveryJobId())).isEqualTo(1);
    }

    private UUID createAttempt() {
        UUID attemptId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO payment_attempt (
                    order_id, attempt_id, state, payment_phase, authorization_active, total_won
                ) VALUES (?, ?, 'UNKNOWN', 'CAPTURE', TRUE, 1000)
                """, UUID.randomUUID(), attemptId);
        return attemptId;
    }
}
