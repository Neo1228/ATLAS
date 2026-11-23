package com.atlas.payment;

import static org.assertj.core.api.Assertions.assertThat;

import com.atlas.payment.service.PayPalReportMatchQuery;
import com.atlas.payment.dto.response.PayPalReportMatchCandidateResponse;
import com.atlas.payment.dto.response.PayPalReportMatchKind;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@SpringBootTest
@Testcontainers
class PayPalReportMatchQueryIntegrationTest extends PayPalIntegrationTestSupport {
    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18.6");

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry properties) {
        properties.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        properties.add("spring.datasource.username", POSTGRES::getUsername);
        properties.add("spring.datasource.password", POSTGRES::getPassword);
        properties.add("atlas.admission.expiry-poll-interval", () -> "PT24H");
        properties.add("atlas.orders.expiry-poll-interval", () -> "PT24H");
    }

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private PayPalReportMatchQuery matchQuery;

    @Test
    void returnsOnlyReadOnlyProviderIdFactsAndConfirmationState() {
        String captureId = "capture-" + UUID.randomUUID();
        String pendingCaptureId = "pending-capture-" + UUID.randomUUID();
        String refundId = "refund-" + UUID.randomUUID();
        String pendingRefundId = "pending-refund-" + UUID.randomUUID();
        UUID attemptId = insertAttempt(captureId, "CAPTURED", 10_000L);
        UUID pendingAttemptId = insertAttempt(pendingCaptureId, "UNKNOWN", 2_000L);
        insertRefund(attemptId, captureId, refundId, 1_000L, "COMPLETED");
        insertRefund(pendingAttemptId, pendingCaptureId, pendingRefundId, 500L, "PENDING");

        List<PayPalReportMatchCandidateResponse> result = matchQuery.findByRecordIds(
                Set.of(captureId, pendingCaptureId, refundId, pendingRefundId, "not-found"));

        assertThat(result).containsExactlyInAnyOrder(
                new PayPalReportMatchCandidateResponse(captureId, PayPalReportMatchKind.CAPTURE, true, captureId, 10_000L, "USD"),
                new PayPalReportMatchCandidateResponse(pendingCaptureId, PayPalReportMatchKind.CAPTURE, false, pendingCaptureId, 2_000L, "USD"),
                new PayPalReportMatchCandidateResponse(refundId, PayPalReportMatchKind.REFUND, true, captureId, 1_000L, "USD"),
                new PayPalReportMatchCandidateResponse(pendingRefundId, PayPalReportMatchKind.REFUND, false, pendingCaptureId, 500L, "USD"));
    }

    private UUID insertAttempt(String captureId, String state, long providerAmountMinor) {
        UUID attemptId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO payment_attempt
                    (order_id, attempt_id, state, payment_phase, authorization_active, total_won,
                     provider_capture_id, provider_amount_minor, provider_currency,
                     fx_rate_won_per_usd, fx_rate_source, fx_rate_as_of)
                VALUES (?, ?, ?, 'CAPTURE', FALSE, ?, ?, ?, 'USD', 1300.0, 'integration-test', DATE '2026-11-16')
                """, UUID.randomUUID(), attemptId, state, providerAmountMinor, captureId, providerAmountMinor);
        return attemptId;
    }

    private void insertRefund(UUID attemptId, String captureId, String refundId,
            long providerAmountMinor, String state) {
        jdbc.update("""
                INSERT INTO payment_refund_operation
                    (refund_operation_id, attempt_id, capture_id, idempotency_key, amount_won, currency,
                     provider_amount_minor, provider_currency, provider_request_id, provider_refund_id,
                     provider_status, refund_state, created_at, updated_at)
                VALUES (?, ?, ?, ?, 1000, 'KRW', ?, 'USD', ?, ?, ?, ?, now(), now())
                """, UUID.randomUUID(), attemptId, captureId, "key-" + UUID.randomUUID(),
                providerAmountMinor, UUID.randomUUID(), refundId, state,
                "COMPLETED".equals(state) ? "COMPLETED" : "PENDING");
    }
}
