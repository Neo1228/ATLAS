package com.atlas.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.atlas.payment.dto.response.PaymentResponse;
import com.atlas.payment.dto.response.PaymentRefundResponse;
import com.atlas.payment.persistence.state.PaymentState;
import com.atlas.payment.persistence.state.PaymentRefundState;
import com.atlas.payment.dto.response.PaymentWebhookResult;
import com.atlas.payment.exception.PaymentException;
import com.atlas.payment.provider.PaymentWebhookEvent;
import com.atlas.payment.provider.PaymentWebhookSignature;
import com.atlas.payment.service.PaymentService;
import com.atlas.payment.persistence.entity.PaymentAttempt;
import com.atlas.payment.provider.PaymentOperation;
import com.atlas.payment.provider.PaymentProvider;
import com.atlas.payment.service.FxConversionService;
import com.atlas.payment.provider.PaymentProviderResult;
import com.atlas.payment.provider.PaymentRefundProviderResult;
import com.atlas.payment.provider.PaymentRefundRequest;
import com.atlas.payment.provider.PaymentRequest;
import com.atlas.payment.repository.PaymentAttemptRepository;
import com.atlas.payment.repository.PaymentRefundRepository;
import com.atlas.payment.repository.PaymentWebhookEventRepository;
import com.atlas.payment.service.impl.PaymentServiceImpl;
import com.atlas.application.service.PayPalWebhookService;
import com.atlas.order.service.CheckoutPaymentService;
import com.atlas.order.service.OrderRefundService;
import com.atlas.order.service.OrderService;
import org.springframework.jdbc.core.JdbcTemplate;
import java.math.BigDecimal;
import java.util.UUID;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
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
        properties.add("spring.datasource.hikari.connection-init-sql", () -> "SET lock_timeout TO '2s'");
    }

    @Autowired
    private PaymentService payments;
    @Autowired
    private PayPalWebhookService webhookService;
    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private PaymentAttemptRepository paymentAttempts;
    @Autowired
    private PaymentRefundRepository paymentRefunds;
    @Autowired
    private PaymentWebhookEventRepository paymentWebhookEvents;
    @Autowired
    private PlatformTransactionManager transactionManager;
    @Autowired
    private PaymentProvider paymentProvider;
    @Autowired
    private FxConversionService fxConversionService;
    @Value("${ATLAS_PUBLIC_BASE_URL:http://127.0.0.1:8080}")
    private String publicBaseUrl;

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
                .contains("\"currency_code\":\"USD\"")
                .contains("\"value\":\"9.68\"")
                .contains("\"return_url\":\"http://127.0.0.1:18080/api/orders/" + orderId + "/payment/return\"")
                .contains("\"cancel_url\":\"http://127.0.0.1:18080/api/orders/" + orderId + "/payment/cancel\"");
        RecordedRequest rateRequest = ECOS.takeRequest(2, TimeUnit.SECONDS);
        assertThat(rateRequest).isNotNull();
        assertThat(rateRequest.getPath()).contains("/json/kr/1/10/731Y001/D/");
        PaymentAttempt attempt = paymentAttempts.findById(pending.attemptId()).orElseThrow();
        assertThat(attempt.getTotalWon()).isEqualTo(12900L);
        assertThat(attempt.getProviderAmountMinor()).isEqualTo(968L);
        assertThat(attempt.getProviderCurrency()).isEqualTo("USD");
        assertThat(attempt.getFxRateWonPerUsd()).isEqualByComparingTo("1332.4");
        assertThat(attempt.getFxRateSource()).isEqualTo("BOK_ECOS_731Y001_0000001");
        assertThat(attempt.getFxRateAsOf()).isNotNull();

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
                .contains("\"currency_code\":\"USD\"")
                .contains("\"value\":\"9.68\"")
                .contains("\"final_capture\":true");

        PaymentResponse approvalReplay = payments.completeApproval(orderId, pending.providerOrderId());
        assertThat(approvalReplay.state()).isEqualTo(PaymentState.CAPTURED);
        PaymentResponse replay = payments.reconcile(orderId, captureKey);
        assertThat(replay.attemptId()).isEqualTo(captured.attemptId());
        assertThat(replay.state()).isEqualTo(PaymentState.CAPTURED);
        assertThat(PAYPAL.takeRequest(200, TimeUnit.MILLISECONDS)).isNull();
        assertThat(ECOS.takeRequest(200, TimeUnit.MILLISECONDS)).isNull();
    }

    @Test
    void reconcileVoidedOrderStatusIsTerminal() {
        UUID orderId = UUID.randomUUID();
        PaymentResponse pending = payments.submitInitial(orderId, BigDecimal.valueOf(12900));
        assertThat(pending.state()).isEqualTo(PaymentState.AUTHORIZATION_PENDING);

        queueOrderStatus("VOIDED");
        PaymentResponse voided = payments.reconcile(orderId, "reconcile-voided-order");

        assertThat(voided.state()).isEqualTo(PaymentState.VOIDED);
        assertThat(voided.authorizationActive()).isFalse();
    }

    @Test
    void reconcileCompletedOrderUsesNestedCaptureStatus() {
        UUID orderId = UUID.randomUUID();
        PaymentResponse pending = payments.submitInitial(orderId, BigDecimal.valueOf(12900));
        assertThat(pending.state()).isEqualTo(PaymentState.AUTHORIZATION_PENDING);

        queueOrderStatus("COMPLETED");
        queueOrderCaptureStatus("COMPLETED");
        PaymentResponse captured = payments.reconcile(orderId, "reconcile-completed-order");

        assertThat(captured.state()).isEqualTo(PaymentState.CAPTURED);
        assertThat(captured.providerCaptureId()).isNotBlank();
        assertThat(captured.authorizationActive()).isFalse();
    }

    @Test
    void completedOrderWithoutPaymentResourceRemainsUnknown() {
        UUID orderId = UUID.randomUUID();
        payments.submitInitial(orderId, BigDecimal.valueOf(12900));

        queueOrderStatus("COMPLETED");
        queueOrderCaptureStatus("NONE");
        PaymentResponse unknown = payments.reconcile(orderId, "reconcile-unknown-completed-order");

        assertThat(unknown.state()).isEqualTo(PaymentState.UNKNOWN);
        assertThat(unknown.authorizationActive()).isTrue();
    }

    @Test
    void payerActionRequiredOrderUsesPayerActionLink() {
        UUID orderId = UUID.randomUUID();
        queueOrderCreateStatus("PAYER_ACTION_REQUIRED");
        queueOrderApprovalLinkRelation("payer-action");

        PaymentResponse pending = payments.submitInitial(orderId, BigDecimal.valueOf(12900));

        assertThat(pending.state()).isEqualTo(PaymentState.AUTHORIZATION_PENDING);
        assertThat(pending.approvalUrl()).contains("/approve/");
    }

    @Test
    void createApprovalStatusIsVerifiedAgainstCurrentOrderBeforeAuthorization() {
        UUID orderId = UUID.randomUUID();
        queueOrderCreateStatus("APPROVED");
        queueOrderStatus("PAYER_ACTION_REQUIRED");

        PaymentResponse pending = payments.submitInitial(orderId, BigDecimal.valueOf(12900));

        assertThat(pending.state()).isEqualTo(PaymentState.AUTHORIZATION_PENDING);
    }

    @Test
    void lostCaptureResponseIsRecoveredFromThePayPalOrder() throws Exception {
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
        RecordedRequest orderStatus = nextPayPalRequestAtPath(
                "/v2/checkout/orders/" + pending.providerOrderId());
        assertThat(orderStatus.getMethod()).isEqualTo("GET");
        assertThat(captureRequestCount()).isEqualTo(1);
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
    void pendingRefundIsRecoveredByProviderGetAndReplayKeepsProviderRefundId() throws Exception {
        UUID orderId = UUID.randomUUID();
        PaymentResponse pending = payments.submitInitial(orderId, BigDecimal.valueOf(17200));
        payments.completeApproval(orderId, pending.providerOrderId());
        PaymentResponse captured = payments.reconcile(orderId, "capture-for-refund-" + orderId);
        assertThat(captured.state()).isEqualTo(PaymentState.CAPTURED);

        String refundKey = "refund-pending-" + orderId;
        queueRefundStatus("PENDING");
        PaymentRefundResponse refundPending = payments.refundOrder(orderId, refundKey, 17200);
        assertThat(refundPending.status()).isEqualTo(PaymentRefundState.PENDING);
        assertThat(refundPending.providerRefundId()).isNotBlank();
        RecordedRequest refundRequest = nextPayPalRequestAtPath(
                "/v2/payments/captures/" + captured.providerCaptureId() + "/refund");
        assertThat(refundRequest.getBody().readUtf8())
                .contains("\"currency_code\":\"USD\"")
                .contains("\"value\":\"12.91\"");

        PaymentRefundResponse completed = payments.refundOrder(orderId, refundKey, 17200);
        assertThat(completed.status()).isEqualTo(PaymentRefundState.COMPLETED);
        assertThat(completed.providerRefundId()).isEqualTo(refundPending.providerRefundId());
        PaymentRefundResponse replay = payments.refundOrder(orderId, refundKey, 17200);
        assertThat(replay.refundOperationId()).isEqualTo(completed.refundOperationId());
        assertThat(replay.providerRefundId()).isEqualTo(completed.providerRefundId());
    }
    @Test
    void partialRefundsReuseFxSnapshotAndCapRoundingAtCapturedUsdAmount() throws Exception {
        UUID orderId = UUID.randomUUID();
        PaymentResponse pending = payments.submitInitial(orderId, BigDecimal.valueOf(1000));
        payments.completeApproval(orderId, pending.providerOrderId());
        PaymentResponse captured = payments.reconcile(orderId, "capture-partial-refund-" + orderId);
        assertThat(captured.state()).isEqualTo(PaymentState.CAPTURED);

        PaymentRefundResponse first = payments.refundOrder(orderId, "partial-first-" + orderId, 500);
        PaymentRefundResponse second = payments.refundOrder(orderId, "partial-second-" + orderId, 500);
        assertThat(first.status()).isEqualTo(PaymentRefundState.COMPLETED);
        assertThat(second.status()).isEqualTo(PaymentRefundState.COMPLETED);

        String refundPath = "/v2/payments/captures/" + captured.providerCaptureId() + "/refund";
        RecordedRequest firstRequest = nextPayPalRequestAtPath(refundPath);
        RecordedRequest secondRequest = nextPayPalRequestAtPath(refundPath);
        assertThat(firstRequest.getBody().readUtf8())
                .contains("\"currency_code\":\"USD\"")
                .contains("\"value\":\"0.38\"");
        assertThat(secondRequest.getBody().readUtf8())
                .contains("\"currency_code\":\"USD\"")
                .contains("\"value\":\"0.37\"");

        PaymentAttempt attempt = paymentAttempts.findById(captured.attemptId()).orElseThrow();
        assertThat(attempt.getTotalWon()).isEqualTo(1000L);
        assertThat(attempt.getProviderAmountMinor()).isEqualTo(75L);
        assertThat(paymentRefunds.refundedAmountForAttempt(captured.attemptId())).isEqualTo(1000L);
        assertThat(paymentRefunds.refundedProviderAmountForAttempt(captured.attemptId())).isEqualTo(75L);
    }
    @Test
    void webhookSignatureIsVerifiedAndDuplicateCaptureEventIsIdempotent() throws Exception {
        UUID orderId = UUID.randomUUID();
        PaymentResponse pending = payments.submitInitial(orderId, BigDecimal.valueOf(17200));
        payments.completeApproval(orderId, pending.providerOrderId());
        PaymentResponse captured = payments.reconcile(orderId, "webhook-capture-" + orderId);

        String eventId = "WH-" + UUID.randomUUID();
        String captureId = captured.providerCaptureId();
        String rawEvent = captureWebhook(eventId, captured.providerOrderId(), captureId, "12.91", "USD");
        PaymentWebhookSignature signature = new PaymentWebhookSignature(
                "SHA256withRSA", "https://local.test/cert", "tx-local", "signature-local", "2026-11-09T00:00:00Z");
        PaymentWebhookEvent event = payments.verifyPayPalWebhook(signature, rawEvent);
        assertThat(event.eventId()).isEqualTo(eventId);
        assertThat(event.providerOrderId()).isEqualTo(captured.providerOrderId());
        assertThat(event.resourceId()).isEqualTo(captureId);
        assertThat(event.resourceAmount()).isEqualTo(new BigDecimal("12.91"));
        assertThat(event.resourceCurrency()).isEqualTo("USD");
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
    void captureWebhookWaitsForOwnerLockBeforeTakingPaymentRowLock() throws Exception {
        UUID orderId = UUID.randomUUID();
        String captureId = "CAPTURE-LOCK-" + UUID.randomUUID();
        LockOrderPaymentProvider provider = new LockOrderPaymentProvider(paymentProvider, captureId);
        CountDownLatch webhookLockAttempted = new CountDownLatch(1);
        AtomicReference<Thread> webhookLockThread = new AtomicReference<>();
        PaymentServiceImpl payments = new ObservedPaymentService(paymentAttempts, paymentRefunds,
                paymentWebhookEvents, transactionManager, provider, fxConversionService, publicBaseUrl,
                webhookLockAttempted, webhookLockThread);
        PaymentResponse pending = payments.submitInitial(orderId, BigDecimal.valueOf(17200));
        PaymentResponse authorized = payments.completeApproval(orderId, pending.providerOrderId());
        PaymentAttempt attempt = paymentAttempts.findById(authorized.attemptId()).orElseThrow();
        String amount = BigDecimal.valueOf(attempt.getProviderAmountMinor(), 2).toPlainString();
        String eventId = "WH-" + UUID.randomUUID();
        PaymentWebhookSignature signature = new PaymentWebhookSignature(
                "SHA256withRSA", "https://local.test/cert", "tx-local", "signature-local",
                "2026-11-09T00:00:00Z");
        String rawBody = captureWebhook(eventId, authorized.providerOrderId(), captureId,
                amount, attempt.getProviderCurrency());
        CountDownLatch downstreamReconciliationEntered = new CountDownLatch(1);
        OrderService orders = mock(OrderService.class);
        doAnswer(invocation -> {
            downstreamReconciliationEntered.countDown();
            payments.reconcile(orderId, invocation.getArgument(1, String.class));
            return null;
        }).when(orders).reconcilePayment(eq(orderId), anyString());
        PayPalWebhookService concurrentWebhook = new PayPalWebhookService(transactionManager, payments, orders,
                mock(CheckoutPaymentService.class), mock(OrderRefundService.class));
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<PaymentResponse> capture = executor.submit(
                    () -> payments.reconcile(orderId, "sync-capture-" + orderId));
            assertThat(provider.captureStarted.await(5, TimeUnit.SECONDS)).isTrue();

            Future<?> webhook = executor.submit(() -> concurrentWebhook.handle(signature, rawBody));
            assertThat(webhookLockAttempted.await(5, TimeUnit.SECONDS)).isTrue();
            awaitBlocked(webhookLockThread.get());
            assertThat(paymentAttemptRowLockedNowait(orderId)).isFalse();

            provider.releaseCapture.countDown();
            assertThat(capture.get(10, TimeUnit.SECONDS).state()).isEqualTo(PaymentState.CAPTURED);
            webhook.get(10, TimeUnit.SECONDS);
            assertThat(downstreamReconciliationEntered.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(provider.captureCalls.get()).isEqualTo(1);
            assertThat(payments.find(orderId).state()).isEqualTo(PaymentState.CAPTURED);
            assertThat(jdbc.queryForObject(
                    "SELECT processing_status FROM payment_provider_webhook_event WHERE event_id = ?",
                    String.class, eventId)).isEqualTo("PROCESSED");
        } finally {
            provider.releaseCapture.countDown();
            executor.shutdownNow();
            executor.awaitTermination(5, TimeUnit.SECONDS);
        }
    }

    @Test
    void sameCaptureWebhookRedeliveryRetriesOrderReconciliationAfterEventCommit() {
        UUID orderId = UUID.randomUUID();
        PaymentResponse pending = payments.submitInitial(orderId, BigDecimal.valueOf(17200));
        PaymentResponse authorized = payments.completeApproval(orderId, pending.providerOrderId());
        PaymentAttempt attempt = paymentAttempts.findById(authorized.attemptId()).orElseThrow();
        String eventId = "WH-RECONCILE-RETRY-" + UUID.randomUUID();
        String captureId = "CAPTURE-RECONCILE-RETRY-" + UUID.randomUUID();
        String amount = BigDecimal.valueOf(attempt.getProviderAmountMinor(), 2).toPlainString();
        PaymentWebhookSignature signature = new PaymentWebhookSignature(
                "SHA256withRSA", "https://local.test/cert", "tx-local", "signature-local",
                "2026-11-09T00:00:00Z");
        String rawBody = captureWebhook(eventId, authorized.providerOrderId(), captureId,
                amount, attempt.getProviderCurrency());
        OrderService orders = mock(OrderService.class);
        AtomicInteger reconciliationCalls = new AtomicInteger();
        IllegalStateException firstReconciliationFailure = new IllegalStateException("Order reconciliation failed");
        doAnswer(invocation -> {
            if (reconciliationCalls.incrementAndGet() == 1) throw firstReconciliationFailure;
            return null;
        }).when(orders).reconcilePayment(eq(orderId), eq(eventId));
        PayPalWebhookService webhook = new PayPalWebhookService(transactionManager, payments, orders,
                mock(CheckoutPaymentService.class), mock(OrderRefundService.class));

        assertThatThrownBy(() -> webhook.handle(signature, rawBody)).isSameAs(firstReconciliationFailure);
        assertThat(jdbc.queryForObject(
                "SELECT processing_status FROM payment_provider_webhook_event WHERE event_id = ?",
                String.class, eventId)).isEqualTo("PROCESSED");
        assertThat(payments.find(orderId).state()).isEqualTo(PaymentState.CAPTURED);
        assertThat(payments.find(orderId).providerCaptureId()).isEqualTo(captureId);

        webhook.handle(signature, rawBody);

        assertThat(reconciliationCalls).hasValue(2);
        verify(orders, times(2)).reconcilePayment(orderId, eventId);
        assertThat(jdbc.queryForObject(
                "SELECT processing_status FROM payment_provider_webhook_event WHERE event_id = ?",
                String.class, eventId)).isEqualTo("PROCESSED");
    }

    @Test
    void unmatchedCaptureWebhookRemainsRetryableWhileMappedMismatchIsQuarantined() throws Exception {
        assertCaptureWebhookMismatchIsQuarantined("12.92", "USD");
        assertCaptureWebhookMismatchIsQuarantined("12.91", "KRW");
        assertUnknownCaptureWebhookCanBeRetried();
    }

    private void assertCaptureWebhookMismatchIsQuarantined(String amount, String currency) throws Exception {
        UUID orderId = UUID.randomUUID();
        PaymentResponse pending = payments.submitInitial(orderId, BigDecimal.valueOf(17200));
        PaymentResponse authorized = payments.completeApproval(orderId, pending.providerOrderId());
        String eventId = "WH-" + UUID.randomUUID();
        String captureId = "CAPTURE-WH-" + UUID.randomUUID();
        PaymentWebhookSignature signature = new PaymentWebhookSignature(
                "SHA256withRSA", "https://local.test/cert", "tx-local", "signature-local",
                "2026-11-09T00:00:00Z");
        String rawBody = captureWebhook(eventId, authorized.providerOrderId(), captureId, amount, currency);

        webhookService.handle(signature, rawBody);
        assertThat(nextPayPalRequestAtPath("/v1/notifications/verify-webhook-signature")).isNotNull();
        assertThat(jdbc.queryForObject(
                "SELECT processing_status FROM payment_provider_webhook_event WHERE event_id = ?",
                String.class, eventId)).isEqualTo("FAILED");
        PaymentResponse unchanged = payments.find(orderId);
        assertThat(unchanged).usingRecursiveComparison().isEqualTo(authorized);
        assertThat(unchanged.state()).isEqualTo(PaymentState.AUTHORIZED);
        assertThat(unchanged.providerCaptureId()).isNull();

        PaymentWebhookEvent rejectedEvent = payments.verifyPayPalWebhook(signature, rawBody);
        assertThat(nextPayPalRequestAtPath("/v1/notifications/verify-webhook-signature")).isNotNull();
        PaymentWebhookResult duplicate = payments.processVerifiedPayPalWebhook(rejectedEvent);
        assertThat(duplicate.duplicate()).isTrue();
        assertThat(payments.find(orderId).state()).isEqualTo(PaymentState.AUTHORIZED);
    }

    private void assertUnknownCaptureWebhookCanBeRetried() throws Exception {
        UUID orderId = UUID.randomUUID();
        PaymentResponse pending = payments.submitInitial(orderId, BigDecimal.valueOf(17200));
        PaymentResponse authorized = payments.completeApproval(orderId, pending.providerOrderId());
        String eventId = "WH-" + UUID.randomUUID();
        String captureId = "CAPTURE-WH-" + UUID.randomUUID();
        PaymentWebhookSignature signature = new PaymentWebhookSignature(
                "SHA256withRSA", "https://local.test/cert", "tx-local", "signature-local",
                "2026-11-09T00:00:00Z");
        String unmatchedRawBody = captureWebhook(
                eventId, authorized.providerOrderId() + "-unknown", captureId, "12.91", "USD");
        assertThatThrownBy(() -> webhookService.handle(signature, unmatchedRawBody))
                .isInstanceOf(PaymentException.class)
                .hasMessageContaining("correlated yet");
        assertThat(nextPayPalRequestAtPath("/v1/notifications/verify-webhook-signature")).isNotNull();
        PaymentWebhookEvent unmatched = payments.verifyPayPalWebhook(signature, unmatchedRawBody);
        assertThat(nextPayPalRequestAtPath("/v1/notifications/verify-webhook-signature")).isNotNull();
        PaymentWebhookResult retryable = payments.processVerifiedPayPalWebhook(unmatched);
        assertThat(retryable.accepted()).isFalse();
        assertThat(retryable.retryable()).isTrue();
        assertThat(retryable.duplicate()).isFalse();
        assertThat(payments.find(orderId)).usingRecursiveComparison().isEqualTo(authorized);

        PaymentWebhookEvent corrected = payments.verifyPayPalWebhook(signature,
                captureWebhook(eventId, authorized.providerOrderId(), captureId, "12.91", "USD"));
        assertThat(nextPayPalRequestAtPath("/v1/notifications/verify-webhook-signature")).isNotNull();
        PaymentWebhookResult accepted = payments.processVerifiedPayPalWebhook(corrected);
        assertThat(accepted.accepted()).isTrue();
        assertThat(accepted.duplicate()).isFalse();
        assertThat(payments.find(orderId).state()).isEqualTo(PaymentState.CAPTURED);
    }


    @Test
    void captureReservationArbitratesAcrossServiceInstancesAndProtectsSuccessFromStaleRetry() throws Exception {
        UUID orderId = UUID.randomUUID();
        BigDecimal amountWon = BigDecimal.valueOf(12900);
        BarrierPaymentProvider barrier = new BarrierPaymentProvider(paymentProvider);
        PaymentServiceImpl first = new PaymentServiceImpl(paymentAttempts, paymentRefunds,
                paymentWebhookEvents, transactionManager, barrier, fxConversionService, publicBaseUrl);
        PaymentServiceImpl second = new PaymentServiceImpl(paymentAttempts, paymentRefunds,
                paymentWebhookEvents, transactionManager, barrier, fxConversionService, publicBaseUrl);
        ExecutorService executor = Executors.newFixedThreadPool(3);
        try {
            PaymentResponse pending = first.submitInitial(orderId, amountWon);
            PaymentResponse authorized = first.completeApproval(orderId, pending.providerOrderId());
            assertThat(authorized.state()).isEqualTo(PaymentState.AUTHORIZED);

            Future<PaymentResponse> capture = executor.submit(
                    () -> first.reconcile(orderId, "capture-first-" + orderId));
            assertThat(barrier.firstCaptureStarted.await(5, TimeUnit.SECONDS)).isTrue();

            PaymentResponse voidAttempt = second.voidAuthorization(orderId, "void-race-" + orderId);
            assertThat(voidAttempt.state()).isEqualTo(PaymentState.CAPTURE_PENDING);
            assertThat(barrier.voidCalls.get()).isZero();

            Future<PaymentResponse> samePhaseRetry = executor.submit(
                    () -> second.reconcile(orderId, "capture-retry-" + orderId));
            assertThat(barrier.bothCaptureCallsStarted.await(5, TimeUnit.SECONDS)).isTrue();
            Future<PaymentState> observed = executor.submit(() -> new TransactionTemplate(transactionManager)
                    .execute(status -> paymentAttempts.findWithLockByOrderId(orderId).orElseThrow()).getState());
            assertThat(observed.get(5, TimeUnit.SECONDS)).isEqualTo(PaymentState.CAPTURE_PENDING);

            barrier.releaseFirstCapture.countDown();
            assertThat(capture.get(10, TimeUnit.SECONDS).state()).isEqualTo(PaymentState.CAPTURED);
            barrier.releaseSecondCapture.countDown();
            assertThat(samePhaseRetry.get(10, TimeUnit.SECONDS).state()).isEqualTo(PaymentState.CAPTURED);
            assertThat(barrier.captureRequestIds).hasSize(2).allMatch(barrier.captureRequestIds.get(0)::equals);
            assertThat(barrier.voidCalls.get()).isZero();
        } finally {
            barrier.releaseFirstCapture.countDown();
            barrier.releaseSecondCapture.countDown();
            executor.shutdownNow();
            executor.awaitTermination(5, TimeUnit.SECONDS);
        }
    }
    private static String captureWebhook(String eventId, String providerOrderId, String captureId,
            String amount, String currency) {
        return "{\"id\":\"" + eventId
                + "\",\"event_type\":\"PAYMENT.CAPTURE.COMPLETED\",\"resource\":{\"id\":\"" + captureId
                + "\",\"status\":\"COMPLETED\",\"amount\":{\"currency_code\":\"" + currency
                + "\",\"value\":\"" + amount
                + "\"},\"supplementary_data\":{\"related_ids\":{\"order_id\":\"" + providerOrderId + "\"}}}}";
    }

    private boolean paymentAttemptRowLockedNowait(UUID orderId) {
        try {
            jdbc.queryForObject("SELECT TRUE FROM payment_attempt WHERE order_id = ? FOR UPDATE NOWAIT",
                    Boolean.class, orderId);
            return false;
        } catch (CannotAcquireLockException lockUnavailable) {
            return true;
        }
    }

    private static void awaitBlocked(Thread thread) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (thread != null && thread.getState() != Thread.State.BLOCKED && System.nanoTime() < deadline) {
            Thread.sleep(1);
        }
        assertThat(thread).isNotNull();
        assertThat(thread.getState()).isEqualTo(Thread.State.BLOCKED);
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

    private static final class BarrierPaymentProvider implements PaymentProvider {
        private final PaymentProvider delegate;
        private final CountDownLatch firstCaptureStarted = new CountDownLatch(1);
        private final CountDownLatch bothCaptureCallsStarted = new CountDownLatch(2);
        private final CountDownLatch releaseFirstCapture = new CountDownLatch(1);
        private final CountDownLatch releaseSecondCapture = new CountDownLatch(1);
        private final AtomicInteger captureCalls = new AtomicInteger();
        private final AtomicInteger voidCalls = new AtomicInteger();
        private final List<String> captureRequestIds = new CopyOnWriteArrayList<>();

        private BarrierPaymentProvider(PaymentProvider delegate) {
            this.delegate = delegate;
        }
        @Override
        public String providerCurrency() { return delegate.providerCurrency(); }

        @Override
        public boolean requiresFxSnapshot() { return delegate.requiresFxSnapshot(); }

        @Override
        public PaymentProviderResult perform(PaymentRequest request) {
            if (request.operation() == PaymentOperation.CAPTURE) {
                int call = captureCalls.incrementAndGet();
                captureRequestIds.add(request.captureRequestId());
                bothCaptureCallsStarted.countDown();
                if (call == 1) {
                    firstCaptureStarted.countDown();
                    await(releaseFirstCapture);
                } else if (call == 2) {
                    await(releaseSecondCapture);
                    throw new PaymentException(PaymentException.Code.PAYMENT_PROVIDER_UNAVAILABLE,
                            "Simulated stale capture retry failure");
                }
            } else if (request.operation() == PaymentOperation.VOID) {
                voidCalls.incrementAndGet();
            }
            return delegate.perform(request);
        }

        @Override
        public PaymentProviderResult completeApproval(PaymentRequest request, String returnedProviderOrderId) {
            return delegate.completeApproval(request, returnedProviderOrderId);
        }

        @Override
        public PaymentRefundProviderResult refund(PaymentRefundRequest request) {
            return delegate.refund(request);
        }

        @Override
        public PaymentRefundProviderResult getRefund(String refundId) {
            return delegate.getRefund(refundId);
        }

        @Override
        public PaymentWebhookEvent verifyWebhook(PaymentWebhookSignature signature, String rawBody) {
            return delegate.verifyWebhook(signature, rawBody);
        }

        private static void await(CountDownLatch latch) {
            try {
                if (!latch.await(15, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("Timed out while provider request was held");
                }
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Interrupted while provider request was held", exception);
            }
        }
    }

    private static final class LockOrderPaymentProvider implements PaymentProvider {
        private final PaymentProvider delegate;
        private final String captureId;
        private final CountDownLatch captureStarted = new CountDownLatch(1);
        private final CountDownLatch releaseCapture = new CountDownLatch(1);
        private final AtomicInteger captureCalls = new AtomicInteger();

        private LockOrderPaymentProvider(PaymentProvider delegate, String captureId) {
            this.delegate = delegate;
            this.captureId = captureId;
        }

        @Override
        public String providerCurrency() { return delegate.providerCurrency(); }

        @Override
        public boolean requiresFxSnapshot() { return delegate.requiresFxSnapshot(); }

        @Override
        public PaymentProviderResult perform(PaymentRequest request) {
            if (request.operation() != PaymentOperation.CAPTURE) return delegate.perform(request);
            captureCalls.incrementAndGet();
            captureStarted.countDown();
            BarrierPaymentProvider.await(releaseCapture);
            return new PaymentProviderResult(PaymentProviderResult.Outcome.CAPTURED,
                    request.providerOrderId(), request.providerAuthorizationId(), captureId, null);
        }

        @Override
        public PaymentProviderResult completeApproval(PaymentRequest request, String returnedProviderOrderId) {
            return delegate.completeApproval(request, returnedProviderOrderId);
        }

        @Override
        public PaymentRefundProviderResult refund(PaymentRefundRequest request) {
            return delegate.refund(request);
        }

        @Override
        public PaymentRefundProviderResult getRefund(String refundId) {
            return delegate.getRefund(refundId);
        }

        @Override
        public PaymentWebhookEvent verifyWebhook(PaymentWebhookSignature signature, String rawBody) {
            return delegate.verifyWebhook(signature, rawBody);
        }
    }

    private static final class ObservedPaymentService extends PaymentServiceImpl {
        private final CountDownLatch webhookLockAttempted;
        private final AtomicReference<Thread> webhookLockThread;

        private ObservedPaymentService(PaymentAttemptRepository attempts, PaymentRefundRepository refunds,
                PaymentWebhookEventRepository webhookEvents, PlatformTransactionManager transactionManager,
                PaymentProvider provider, FxConversionService fxConversionService, String publicBaseUrl,
                CountDownLatch webhookLockAttempted, AtomicReference<Thread> webhookLockThread) {
            super(attempts, refunds, webhookEvents, transactionManager, provider, fxConversionService,
                    publicBaseUrl);
            this.webhookLockAttempted = webhookLockAttempted;
            this.webhookLockThread = webhookLockThread;
        }

        @Override
        public <T> T withPayPalWebhookOwnerLock(PaymentWebhookEvent event, Supplier<T> action) {
            webhookLockThread.set(Thread.currentThread());
            webhookLockAttempted.countDown();
            return super.withPayPalWebhookOwnerLock(event, action);
        }
    }
}
