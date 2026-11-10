package com.atlas.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.atlas.payment.dto.response.PaymentResponse;
import com.atlas.payment.dto.response.PaymentRefundResponse;
import com.atlas.payment.persistence.state.PaymentState;
import com.atlas.payment.persistence.state.PaymentRefundState;
import com.atlas.payment.dto.response.PaymentWebhookResult;
import com.atlas.payment.exception.PaymentException;
import com.atlas.payment.provider.PaymentWebhookEvent;
import com.atlas.payment.provider.PaymentWebhookSignature;
import com.atlas.payment.service.PaymentService;
import java.math.BigDecimal;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import okhttp3.mockwebserver.RecordedRequest;

@Testcontainers
@SpringBootTest
class PaymentLifecycleIntegrationTest extends PayPalIntegrationTestSupport {
    @Container
    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18.6")
            .withDatabaseName("atlas");

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry properties) {
        properties.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        properties.add("spring.datasource.username", POSTGRES::getUsername);
        properties.add("spring.datasource.password", POSTGRES::getPassword);
        properties.add("atlas.admission.expiry-poll-interval", () -> "PT24H");
        properties.add("atlas.orders.expiry-poll-interval", () -> "PT24H");
    }

    @Autowired
    private PaymentService payments;

    @Test
    void paypalAuthorizationCaptureAndReplayUseTheLocalProvider() throws Exception {
        UUID orderId = UUID.randomUUID();
        BigDecimal amountWon = BigDecimal.valueOf(12900);

        PaymentResponse pending = payments.submitInitial(orderId, amountWon);
        assertThat(pending.state()).isEqualTo(PaymentState.AUTHORIZATION_PENDING);
        assertThat(pending.providerOrderId()).isNotBlank();
        assertThat(pending.approvalUrl()).contains("localhost");

        RecordedRequest firstRequest = takePayPalRequest();
        if ("/v1/oauth2/token".equals(firstRequest.getPath())) {
            assertThat(firstRequest.getHeader("Authorization")).startsWith("Basic ");
            firstRequest = takePayPalRequest();
        }
        RecordedRequest createOrder = firstRequest;
        assertThat(createOrder.getMethod()).isEqualTo("POST");
        assertThat(createOrder.getPath()).isEqualTo("/v2/checkout/orders");
        assertThat(createOrder.getHeader("Authorization")).isEqualTo("Bearer local-access-token");
        assertThat(createOrder.getHeader("PayPal-Request-Id")).isNotBlank();
        assertThat(createOrder.getBody().readUtf8())
                .contains("\"intent\":\"AUTHORIZE\"")
                .contains("\"currency_code\":\"KRW\"")
                .contains("\"value\":\"12900\"");

        PaymentResponse authorized = payments.completeApproval(orderId, pending.providerOrderId());
        assertThat(authorized.state()).isEqualTo(PaymentState.AUTHORIZED);
        RecordedRequest approvedOrder = takePayPalRequest();
        assertThat(approvedOrder.getMethod()).isEqualTo("GET");
        assertThat(approvedOrder.getPath()).isEqualTo("/v2/checkout/orders/" + pending.providerOrderId());
        RecordedRequest authorize = takePayPalRequest();
        assertThat(authorize.getPath()).isEqualTo("/v2/checkout/orders/" + pending.providerOrderId() + "/authorize");
        assertThat(authorize.getHeader("PayPal-Request-Id")).isNotBlank();
        assertThat(authorize.getBody().readUtf8()).isEqualTo("{}");

        String captureKey = "capture-replay-" + orderId;
        PaymentResponse captured = payments.reconcile(orderId, captureKey);
        assertThat(captured.state()).isEqualTo(PaymentState.CAPTURED);
        RecordedRequest capture = takePayPalRequest();
        assertThat(capture.getPath()).contains("/capture");
        assertThat(capture.getHeader("PayPal-Request-Id")).isNotBlank().hasSizeLessThanOrEqualTo(38);
        assertThat(capture.getBody().readUtf8())
                .contains("\"currency_code\":\"KRW\"")
                .contains("\"value\":\"12900\"")
                .contains("\"final_capture\":true");

        PaymentResponse approvalReplay = payments.completeApproval(orderId, pending.providerOrderId());
        assertThat(approvalReplay.state()).isEqualTo(PaymentState.CAPTURED);
        PaymentResponse replay = payments.reconcile(orderId, captureKey);
        assertThat(replay.attemptId()).isEqualTo(captured.attemptId());
        assertThat(replay.state()).isEqualTo(PaymentState.CAPTURED);
        assertThat(PAYPAL.takeRequest(200, TimeUnit.MILLISECONDS)).isNull();
    }

    @Test
    void lostCaptureResponseRetriesWithTheSameProviderRequestId() throws Exception {
        UUID orderId = UUID.randomUUID();
        PaymentResponse pending = payments.submitInitial(orderId, BigDecimal.valueOf(17200));
        PaymentResponse authorized = payments.completeApproval(orderId, pending.providerOrderId());
        assertThat(authorized.state()).isEqualTo(PaymentState.AUTHORIZED);

        String idempotencyKey = "lost-capture-" + orderId;
        disconnectNextCaptureResponse();
        PaymentResponse uncertain = payments.reconcile(orderId, idempotencyKey);
        assertThat(uncertain.state()).isIn(PaymentState.UNKNOWN, PaymentState.CAPTURE_PENDING);
        RecordedRequest firstCapture = nextCaptureRequest();

        PaymentResponse recovered = payments.reconcile(orderId, idempotencyKey);
        assertThat(recovered.state()).isEqualTo(PaymentState.CAPTURED);
        RecordedRequest replayedCapture = nextCaptureRequest();
        assertThat(firstCapture.getHeader("PayPal-Request-Id"))
                .isEqualTo(replayedCapture.getHeader("PayPal-Request-Id"));
        assertThat(captureRequestCount()).isEqualTo(2);
    }

    @Test
    void lostAuthorizationResponseIsUnknownAndRecoversWithTheSameProviderRequestId() throws Exception {
        UUID orderId = UUID.randomUUID();
        PaymentResponse pending = payments.submitInitial(orderId, BigDecimal.valueOf(17200));
        disconnectNextAuthorizationResponse();

        PaymentResponse uncertain = payments.completeApproval(orderId, pending.providerOrderId());
        assertThat(uncertain.state()).isEqualTo(PaymentState.UNKNOWN);
        String authorizePath = "/v2/checkout/orders/" + pending.providerOrderId() + "/authorize";
        RecordedRequest firstAuthorization = nextPayPalRequestAtPath(authorizePath);

        PaymentResponse recovered = payments.reconcile(orderId, "lost-authorization-" + orderId);
        assertThat(recovered.state()).isEqualTo(PaymentState.CAPTURED);
        RecordedRequest retriedAuthorization = nextPayPalRequestAtPath(authorizePath);
        assertThat(retriedAuthorization.getHeader("PayPal-Request-Id"))
                .isEqualTo(firstAuthorization.getHeader("PayPal-Request-Id"));
    }

    @Test
    void pendingRefundIsRecoveredByProviderGetAndReplayKeepsProviderRefundId() {
        UUID orderId = UUID.randomUUID();
        PaymentResponse pending = payments.submitInitial(orderId, BigDecimal.valueOf(17200));
        payments.completeApproval(orderId, pending.providerOrderId());
        assertThat(payments.reconcile(orderId, "capture-for-refund-" + orderId).state())
                .isEqualTo(PaymentState.CAPTURED);

        String refundKey = "refund-pending-" + orderId;
        queueRefundStatus("PENDING");
        PaymentRefundResponse refundPending = payments.refundOrder(orderId, refundKey, 17200);
        assertThat(refundPending.status()).isEqualTo(PaymentRefundState.PENDING);
        assertThat(refundPending.providerRefundId()).isNotBlank();

        PaymentRefundResponse completed = payments.refundOrder(orderId, refundKey, 17200);
        assertThat(completed.status()).isEqualTo(PaymentRefundState.COMPLETED);
        assertThat(completed.providerRefundId()).isEqualTo(refundPending.providerRefundId());
        PaymentRefundResponse replay = payments.refundOrder(orderId, refundKey, 17200);
        assertThat(replay.refundOperationId()).isEqualTo(completed.refundOperationId());
        assertThat(replay.providerRefundId()).isEqualTo(completed.providerRefundId());
    }
    @Test
    void webhookSignatureIsVerifiedAndDuplicateCaptureEventIsIdempotent() throws Exception {
        UUID orderId = UUID.randomUUID();
        PaymentResponse pending = payments.submitInitial(orderId, BigDecimal.valueOf(17200));
        payments.completeApproval(orderId, pending.providerOrderId());
        PaymentResponse captured = payments.reconcile(orderId, "webhook-capture-" + orderId);

        String eventId = "WH-" + UUID.randomUUID();
        String captureId = captured.providerCaptureId();
        String rawEvent = captureWebhook(eventId, captured.providerOrderId(), captureId, "17200", "KRW");
        PaymentWebhookSignature signature = new PaymentWebhookSignature(
                "SHA256withRSA", "https://local.test/cert", "tx-local", "signature-local", "2026-11-09T00:00:00Z");
        PaymentWebhookEvent event = payments.verifyPayPalWebhook(signature, rawEvent);
        assertThat(event.eventId()).isEqualTo(eventId);
        assertThat(event.providerOrderId()).isEqualTo(captured.providerOrderId());
        assertThat(event.resourceId()).isEqualTo(captureId);
        assertThat(event.resourceAmount()).isEqualTo(BigDecimal.valueOf(17200));
        assertThat(event.resourceCurrency()).isEqualTo("KRW");
        RecordedRequest verificationRequest = nextPayPalRequestAtPath(
                "/v1/notifications/verify-webhook-signature");
        assertThat(verificationRequest.getMethod()).isEqualTo("POST");
        assertThat(verificationRequest.getBody().readUtf8())
                .contains("\"webhook_id\":\"local-test-webhook\"")
                .contains("\"id\":\"" + eventId + "\"");
        PaymentWebhookResult accepted = payments.processVerifiedPayPalWebhook(event);
        assertThat(accepted.accepted()).isTrue();
        PaymentWebhookResult duplicate = payments.processVerifiedPayPalWebhook(event);
        assertThat(duplicate.duplicate()).isTrue();
        assertThat(payments.find(orderId).state()).isEqualTo(PaymentState.CAPTURED);
        assertThat(payments.find(orderId).providerCaptureId()).isEqualTo(captureId);

        queueWebhookVerificationStatus("FAILURE");
        assertThatThrownBy(() -> payments.verifyPayPalWebhook(signature, rawEvent))
                .isInstanceOf(PaymentException.class);
        assertThat(nextPayPalRequestAtPath("/v1/notifications/verify-webhook-signature")).isNotNull();
    }

    @Test
    void rejectedCaptureWebhooksDoNotMutatePaymentOrConsumeEventId() throws Exception {
        assertCaptureWebhookRejectedThenAccepted("17200.01", "KRW", false);
        assertCaptureWebhookRejectedThenAccepted("17200", "USD", false);
        assertCaptureWebhookRejectedThenAccepted("17200", "KRW", true);
    }

    private void assertCaptureWebhookRejectedThenAccepted(String amount, String currency, boolean unknownOrder)
            throws Exception {
        UUID orderId = UUID.randomUUID();
        PaymentResponse pending = payments.submitInitial(orderId, BigDecimal.valueOf(17200));
        PaymentResponse authorized = payments.completeApproval(orderId, pending.providerOrderId());
        String eventId = "WH-" + UUID.randomUUID();
        String captureId = "CAPTURE-WH-" + UUID.randomUUID();
        String eventOrderId = unknownOrder ? authorized.providerOrderId() + "-unknown" : authorized.providerOrderId();
        PaymentWebhookSignature signature = new PaymentWebhookSignature(
                "SHA256withRSA", "https://local.test/cert", "tx-local", "signature-local", "2026-11-09T00:00:00Z");

        PaymentWebhookEvent rejectedEvent = payments.verifyPayPalWebhook(signature,
                captureWebhook(eventId, eventOrderId, captureId, amount, currency));
        assertThat(nextPayPalRequestAtPath("/v1/notifications/verify-webhook-signature")).isNotNull();
        PaymentWebhookResult rejected = payments.processVerifiedPayPalWebhook(rejectedEvent);
        assertThat(rejected.accepted()).isFalse();
        assertThat(rejected.duplicate()).isFalse();
        PaymentResponse unchanged = payments.find(orderId);
        assertThat(unchanged).usingRecursiveComparison().isEqualTo(authorized);
        assertThat(unchanged.state()).isEqualTo(PaymentState.AUTHORIZED);
        assertThat(unchanged.providerCaptureId()).isNull();

        PaymentWebhookEvent correctedEvent = payments.verifyPayPalWebhook(signature,
                captureWebhook(eventId, authorized.providerOrderId(), captureId, "17200", "KRW"));
        assertThat(nextPayPalRequestAtPath("/v1/notifications/verify-webhook-signature")).isNotNull();
        PaymentWebhookResult accepted = payments.processVerifiedPayPalWebhook(correctedEvent);
        assertThat(accepted.accepted()).isTrue();
        assertThat(accepted.duplicate()).isFalse();
        PaymentResponse captured = payments.find(orderId);
        assertThat(captured.state()).isEqualTo(PaymentState.CAPTURED);
        assertThat(captured.providerCaptureId()).isEqualTo(captureId);
    }

    private static String captureWebhook(String eventId, String providerOrderId, String captureId,
            String amount, String currency) {
        return "{\"id\":\"" + eventId
                + "\",\"event_type\":\"PAYMENT.CAPTURE.COMPLETED\",\"resource\":{\"id\":\"" + captureId
                + "\",\"status\":\"COMPLETED\",\"amount\":{\"currency_code\":\"" + currency
                + "\",\"value\":\"" + amount
                + "\"},\"supplementary_data\":{\"related_ids\":{\"order_id\":\"" + providerOrderId + "\"}}}}";
    }
    private RecordedRequest nextPayPalRequestAtPath(String path) throws InterruptedException {
        RecordedRequest request;
        while ((request = takePayPalRequest()) != null) {
            if (request.getPath().equals(path)) {
                return request;
            }
        }
        return null;
    }
    private RecordedRequest nextCaptureRequest() throws InterruptedException {
        RecordedRequest request;
        while ((request = takePayPalRequest()) != null) {
            if (request.getPath().contains("/capture")) {
                return request;
            }
        }
        return null;
    }
}
