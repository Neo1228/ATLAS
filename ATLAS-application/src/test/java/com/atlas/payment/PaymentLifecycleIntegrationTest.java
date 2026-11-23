package com.atlas.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.atlas.application.exception.ApplicationException.Code;
import com.atlas.application.exception.ApplicationException;
import com.atlas.application.persistence.projection.ClaimedRetry;
import com.atlas.application.persistence.projection.WebhookRetryEvent;
import com.atlas.application.repository.PayPalWebhookRetryRepository;
import com.atlas.application.service.PayPalWebhookOperatorService;
import com.atlas.application.service.PayPalWebhookRetryWorker;
import com.atlas.application.service.PayPalWebhookService;
import com.atlas.catalog.dto.request.CreateProductRequest;
import com.atlas.catalog.dto.request.RegisterSellerSkuRequest;
import com.atlas.catalog.service.ProductService;
import com.atlas.catalog.service.SellerService;
import com.atlas.inventory.persistence.state.ReservationStatus;
import com.atlas.order.dto.response.CheckoutResponse;
import com.atlas.order.persistence.state.BuyerCheckoutState;
import com.atlas.order.persistence.state.OrderState;
import com.atlas.order.service.CartCheckoutService;
import com.atlas.order.service.CartService;
import com.atlas.order.service.CheckoutPaymentService;
import com.atlas.order.service.OrderRefundService;
import com.atlas.order.service.OrderService;
import com.atlas.payment.dto.request.PaymentRefundRequest;
import com.atlas.payment.dto.request.PaymentRequest;
import com.atlas.payment.dto.request.PaymentWebhookSignatureRequest;
import com.atlas.payment.dto.response.PaymentProviderResponse;
import com.atlas.payment.dto.response.PaymentRefundProviderResponse;
import com.atlas.payment.dto.response.PaymentRefundResponse;
import com.atlas.payment.dto.response.PaymentResponse;
import com.atlas.payment.dto.response.PaymentWebhookEventResponse;
import com.atlas.payment.dto.response.PaymentWebhookResponse;
import com.atlas.payment.exception.PaymentException;
import com.atlas.payment.persistence.entity.PaymentAttempt;
import com.atlas.payment.persistence.state.PaymentRefundState;
import com.atlas.payment.persistence.state.PaymentState;
import com.atlas.payment.provider.PaymentOperation;
import com.atlas.payment.provider.PaymentProvider;
import com.atlas.payment.repository.PaymentAttemptRepository;
import com.atlas.payment.repository.PaymentRecoveryRepository;
import com.atlas.payment.repository.PaymentRefundRepository;
import com.atlas.payment.repository.PaymentWebhookEventRepository;
import com.atlas.payment.service.FxConversionService;
import com.atlas.payment.service.PaymentService;
import com.atlas.payment.service.impl.OwnerLockRegistry;
import com.atlas.payment.service.impl.PaymentRefundWorkflow;
import com.atlas.payment.service.impl.PaymentServiceImpl;
import com.atlas.payment.service.impl.PaymentWebhookProcessor;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.JdkIdGenerator;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

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
        properties.add("atlas.payment.paypal.webhook-retry.worker.poll-interval", () -> "PT24H");
        properties.add("atlas.payment.paypal.webhook-retry.worker.initial-delay", () -> "PT24H");
        properties.add("atlas.payment.paypal.webhook-retry.worker.enabled", () -> "false");
        properties.add("atlas.payment.recovery.worker.enabled", () -> "false");
        properties.add("spring.datasource.hikari.connection-init-sql", () -> "SET lock_timeout TO '2s'");
    }

    @Autowired
    private PaymentService payments;
    @Autowired
    private PayPalWebhookService webhookService;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private CartService carts;
    @Autowired
    private SellerService sellers;
    @Autowired
    private ProductService products;
    @Autowired
    private CartCheckoutService cartCheckouts;
    @Autowired
    private CheckoutPaymentService checkoutPayments;

    @Autowired
    private PaymentAttemptRepository paymentAttempts;
    @Autowired
    private PaymentRefundRepository paymentRefunds;
    @Autowired
    private PaymentWebhookEventRepository paymentWebhookEvents;
    @Autowired
    private PaymentRecoveryRepository paymentRecovery;
    @Autowired
    private PlatformTransactionManager transactionManager;
    @Autowired
    private PaymentProvider paymentProvider;
    @Autowired
    private FxConversionService fxConversionService;
    @Autowired
    private PayPalWebhookOperatorService webhookOperator;
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
        // 결제 1건은 외부 환율 조회를 정확히 1회 만든다. 캐시는 TTL(60초) 안에서만 재사용하므로
        // 새 컨텍스트의 첫 결제는 반드시 ECOS 를 호출한다.
        RecordedRequest rateRequest = ECOS.takeRequest(5, TimeUnit.SECONDS);
        assertThat(rateRequest).as("first payment must call the FX API").isNotNull();
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
        PaymentWebhookSignatureRequest signature = new PaymentWebhookSignatureRequest(
                "SHA256withRSA", "https://local.test/cert", "tx-local", "signature-local", "2026-11-09T00:00:00Z");
        PaymentWebhookEventResponse event = payments.verifyPayPalWebhook(signature, rawEvent);
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
        PaymentWebhookResponse accepted = payments.processVerifiedPayPalWebhook(event);
        assertThat(accepted.accepted()).isTrue();
        PaymentWebhookResponse duplicate = payments.processVerifiedPayPalWebhook(event);
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
        PaymentServiceImpl payments = observedPaymentService(provider, webhookLockAttempted, webhookLockThread);
        PaymentResponse pending = payments.submitInitial(orderId, BigDecimal.valueOf(17200));
        PaymentResponse authorized = payments.completeApproval(orderId, pending.providerOrderId());
        PaymentAttempt attempt = paymentAttempts.findById(authorized.attemptId()).orElseThrow();
        String amount = BigDecimal.valueOf(attempt.getProviderAmountMinor(), 2).toPlainString();
        String eventId = "WH-" + UUID.randomUUID();
        PaymentWebhookSignatureRequest signature = new PaymentWebhookSignatureRequest(
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
        PayPalWebhookService webhookService = new PayPalWebhookService(transactionManager, payments, orders,
                mock(CheckoutPaymentService.class), mock(OrderRefundService.class),
                new PayPalWebhookRetryRepository(jdbc));
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<PaymentResponse> capture = executor.submit(
                    () -> payments.reconcile(orderId, "sync-capture-" + orderId));
            assertThat(provider.captureStarted.await(5, TimeUnit.SECONDS)).isTrue();

            Future<?> webhook = executor.submit(() -> webhookService.handle(signature, rawBody));
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
        PaymentWebhookSignatureRequest signature = new PaymentWebhookSignatureRequest(
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
                mock(CheckoutPaymentService.class), mock(OrderRefundService.class),
                new PayPalWebhookRetryRepository(jdbc));

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
    void sameCaptureWebhookRedeliveryRetriesCheckoutReconciliationAfterEventCommit() {
        long amountWon = 17200L;
        UUID sellerId = sellers.createSeller().sellerId();
        var product = products.createProduct(sellerId, new CreateProductRequest("Webhook retry product", null));
        String skuId = "webhook-retry-" + UUID.randomUUID();
        products.registerSku(sellerId, product.productId(), new RegisterSellerSkuRequest(skuId, amountWon, 2));
        String buyerRef = "buyer-webhook-" + UUID.randomUUID();
        carts.setItemQuantity(sellerId, buyerRef, skuId, 1);
        CheckoutResponse created = cartCheckouts.checkout(buyerRef, "checkout-webhook-" + UUID.randomUUID());
        UUID checkoutId = created.checkoutId();
        CheckoutResponse pending = checkoutPayments.submit(checkoutId);
        assertThat(pending.paymentState()).isEqualTo(PaymentState.AUTHORIZATION_PENDING);
        String providerOrderId = payments.findCheckout(checkoutId).providerOrderId();
        PaymentResponse authorized = payments.completeCheckoutApproval(checkoutId, providerOrderId);
        assertThat(authorized.state()).isEqualTo(PaymentState.AUTHORIZED);
        PaymentAttempt attempt = paymentAttempts.findById(authorized.attemptId()).orElseThrow();
        String eventId = "WH-CHECKOUT-RECONCILE-RETRY-" + UUID.randomUUID();
        String captureId = "CAPTURE-" + UUID.randomUUID();
        String amount = BigDecimal.valueOf(attempt.getProviderAmountMinor(), 2).toPlainString();
        PaymentWebhookSignatureRequest signature = new PaymentWebhookSignatureRequest(
                "SHA256withRSA", "https://local.test/cert", "tx-local", "signature-local",
                "2026-11-09T00:00:00Z");
        String rawBody = captureWebhook(eventId, authorized.providerOrderId(), captureId,
                amount, attempt.getProviderCurrency());
        CheckoutPaymentService checkoutPaymentsProbe = mock(CheckoutPaymentService.class);
        AtomicInteger reconciliationCalls = new AtomicInteger();
        IllegalStateException firstReconciliationFailure = new IllegalStateException(
                "Checkout reconciliation failed");
        doAnswer(invocation -> {
            if (reconciliationCalls.incrementAndGet() == 1) throw firstReconciliationFailure;
            return checkoutPayments.reconcile(invocation.getArgument(0, UUID.class),
                    invocation.getArgument(1, String.class));
        }).when(checkoutPaymentsProbe).reconcile(eq(checkoutId), eq(eventId));
        PayPalWebhookService webhook = new PayPalWebhookService(transactionManager, payments,
                mock(OrderService.class), checkoutPaymentsProbe, mock(OrderRefundService.class),
                new PayPalWebhookRetryRepository(jdbc));

        assertThatThrownBy(() -> webhook.handle(signature, rawBody)).isSameAs(firstReconciliationFailure);
        assertThat(jdbc.queryForObject(
                "SELECT processing_status FROM payment_provider_webhook_event WHERE event_id = ?",
                String.class, eventId)).isEqualTo("PROCESSED");
        PaymentResponse captured = payments.findCheckout(checkoutId);
        assertThat(captured.state()).isEqualTo(PaymentState.CAPTURED);
        assertThat(captured.checkoutId()).isEqualTo(checkoutId);
        assertThat(captured.providerCaptureId()).isEqualTo(captureId);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM settlement_journal WHERE source_attempt_id = ?",
                Integer.class, captured.attemptId())).isZero();
        CheckoutResponse awaitingRedelivery = checkoutPayments.find(checkoutId);
        assertThat(awaitingRedelivery.state()).isEqualTo(BuyerCheckoutState.PAYMENT_PENDING);
        assertThat(awaitingRedelivery.orders()).hasSize(1);
        var pendingOrder = awaitingRedelivery.orders().getFirst();
        assertThat(pendingOrder.state()).isEqualTo(OrderState.PAYMENT_PENDING);
        assertThat(pendingOrder.inventoryState()).isEqualTo(ReservationStatus.RESERVED);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM order_fulfillment WHERE order_id = ?",
                Integer.class, pendingOrder.orderId())).isZero();

        webhook.handle(signature, rawBody);

        assertThat(reconciliationCalls).hasValue(2);
        verify(checkoutPaymentsProbe, times(2)).reconcile(checkoutId, eventId);
        assertThat(jdbc.queryForObject(
                "SELECT processing_status FROM payment_provider_webhook_event WHERE event_id = ?",
                String.class, eventId)).isEqualTo("PROCESSED");
        assertThat(payments.findCheckout(checkoutId)).usingRecursiveComparison().isEqualTo(captured);
        CheckoutResponse reconciled = checkoutPayments.find(checkoutId);
        assertThat(reconciled.state()).isEqualTo(BuyerCheckoutState.PAID);
        assertThat(reconciled.paymentState()).isEqualTo(PaymentState.CAPTURED);
        assertThat(reconciled.orders()).hasSize(1);
        var paidOrder = reconciled.orders().getFirst();
        assertThat(paidOrder.state()).isEqualTo(OrderState.PAID);
        assertThat(paidOrder.paymentState()).isEqualTo(PaymentState.CAPTURED);
        assertThat(paidOrder.inventoryState()).isEqualTo(ReservationStatus.CLAIMED);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM settlement_journal WHERE source_attempt_id = ?",
                Integer.class, captured.attemptId())).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM order_fulfillment WHERE order_id = ?",
                Integer.class, paidOrder.orderId())).isEqualTo(1);
        assertThat(captureRequestCount()).isZero();
    }

    @Test
    void expiredWebhookRetryOwnerCannotCompleteAfterReclaimedCaptureIsApplied() throws Exception {
        long amountWon = 17200L;
        UUID sellerId = sellers.createSeller().sellerId();
        var product = products.createProduct(sellerId, new CreateProductRequest("Webhook lease race product", null));
        String skuId = "webhook-lease-race-" + UUID.randomUUID();
        products.registerSku(sellerId, product.productId(), new RegisterSellerSkuRequest(skuId, amountWon, 2));
        String buyerRef = "buyer-webhook-lease-race-" + UUID.randomUUID();
        carts.setItemQuantity(sellerId, buyerRef, skuId, 1);
        CheckoutResponse created = cartCheckouts.checkout(buyerRef, "checkout-webhook-lease-race-" + UUID.randomUUID());
        UUID checkoutId = created.checkoutId();
        CheckoutResponse pending = checkoutPayments.submit(checkoutId);
        assertThat(pending.paymentState()).isEqualTo(PaymentState.AUTHORIZATION_PENDING);
        String providerOrderId = payments.findCheckout(checkoutId).providerOrderId();
        PaymentResponse authorized = payments.completeCheckoutApproval(checkoutId, providerOrderId);
        assertThat(authorized.state()).isEqualTo(PaymentState.AUTHORIZED);
        PaymentAttempt attempt = paymentAttempts.findById(authorized.attemptId()).orElseThrow();

        String eventId = "WH-LEASE-RACE-" + UUID.randomUUID();
        String captureId = "CAPTURE-LEASE-RACE-" + UUID.randomUUID();
        PaymentWebhookEventResponse event = new PaymentWebhookEventResponse(eventId, "PAYMENT.CAPTURE.COMPLETED",
                captureId, "COMPLETED", providerOrderId, null,
                BigDecimal.valueOf(attempt.getProviderAmountMinor(), 2), attempt.getProviderCurrency());
        CountDownLatch firstWorkerAtPostPaymentTransaction = new CountDownLatch(1);
        CountDownLatch releaseFirstWorker = new CountDownLatch(1);
        AtomicInteger checkoutReconciliationCalls = new AtomicInteger();
        AtomicInteger successfulCompletions = new AtomicInteger();
        AtomicReference<String> rejectedCompletionOwner = new AtomicReference<>();
        CheckoutPaymentService checkoutPaymentsProbe = mock(CheckoutPaymentService.class);
        doAnswer(invocation -> {
            if (checkoutReconciliationCalls.incrementAndGet() == 1) {
                firstWorkerAtPostPaymentTransaction.countDown();
                assertThat(releaseFirstWorker.await(10, TimeUnit.SECONDS)).isTrue();
            }
            return checkoutPayments.reconcile(invocation.getArgument(0, UUID.class),
                    invocation.getArgument(1, String.class));
        }).when(checkoutPaymentsProbe).reconcile(eq(checkoutId), eq(eventId));

        PayPalWebhookRetryRepository retryRepository = new PayPalWebhookRetryRepository(jdbc) {
            @Override
            public boolean complete(String candidateEventId, String leaseOwner) {
                boolean completed = super.complete(candidateEventId, leaseOwner);
                if (completed) successfulCompletions.incrementAndGet();
                else rejectedCompletionOwner.set(leaseOwner);
                return completed;
            }
        };
        retryRepository.enqueue(retryEvent(event));
        PayPalWebhookService retryWebhookService = new PayPalWebhookService(transactionManager, payments,
                mock(OrderService.class), checkoutPaymentsProbe, mock(OrderRefundService.class), retryRepository);
        PayPalWebhookRetryWorker workerA =
                new PayPalWebhookRetryWorker(retryRepository, retryWebhookService, new SimpleMeterRegistry());
        PayPalWebhookRetryWorker workerB =
                new PayPalWebhookRetryWorker(retryRepository, retryWebhookService, new SimpleMeterRegistry());
        ExecutorService workers = Executors.newFixedThreadPool(2);
        try {
            Future<?> firstWorker = workers.submit(workerA::retryDueEvents);
            assertThat(firstWorkerAtPostPaymentTransaction.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(firstWorker.isDone()).isFalse();
            String firstLeaseOwner = jdbc.queryForObject(
                    "SELECT lease_owner FROM paypal_webhook_retry WHERE event_id = ?", String.class, eventId);
            assertThat(firstLeaseOwner).isNotBlank();
            assertThat(jdbc.queryForObject(
                    "SELECT state FROM paypal_webhook_retry WHERE event_id = ?", String.class, eventId))
                    .isEqualTo("PENDING");
            assertThat(jdbc.queryForObject(
                    "SELECT lease_until > clock_timestamp() FROM paypal_webhook_retry WHERE event_id = ?",
                    Boolean.class, eventId)).isTrue();
            assertThat(jdbc.queryForObject(
                    "SELECT processing_status FROM payment_provider_webhook_event WHERE event_id = ?",
                    String.class, eventId)).isEqualTo("PROCESSED");
            assertThat(payments.findCheckout(checkoutId).state()).isEqualTo(PaymentState.CAPTURED);
            CheckoutResponse awaitingReconciliation = checkoutPayments.find(checkoutId);
            assertThat(awaitingReconciliation.state()).isEqualTo(BuyerCheckoutState.PAYMENT_PENDING);
            assertThat(awaitingReconciliation.orders()).hasSize(1);
            assertThat(awaitingReconciliation.orders().getFirst().state()).isEqualTo(OrderState.PAYMENT_PENDING);
            assertThat(awaitingReconciliation.orders().getFirst().inventoryState())
                    .isEqualTo(ReservationStatus.RESERVED);

            assertThat(jdbc.update(
                    "UPDATE paypal_webhook_retry SET lease_until = clock_timestamp() - INTERVAL '1 second' "
                            + "WHERE event_id = ?", eventId)).isEqualTo(1);
            Future<?> secondWorker = workers.submit(workerB::retryDueEvents);
            secondWorker.get(10, TimeUnit.SECONDS);
            assertThat(firstWorker.isDone()).isFalse();
            assertThat(checkoutReconciliationCalls).hasValue(2);
            assertThat(successfulCompletions).hasValue(1);
            assertThat(rejectedCompletionOwner.get()).isNull();

            releaseFirstWorker.countDown();
            firstWorker.get(10, TimeUnit.SECONDS);
        } finally {
            releaseFirstWorker.countDown();
            workers.shutdownNow();
            assertThat(workers.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        }

        assertThat(checkoutReconciliationCalls).hasValue(2);
        assertThat(successfulCompletions).hasValue(1);
        assertThat(rejectedCompletionOwner.get()).isNotBlank();
        assertThat(rejectedCompletionOwner.get()).isEqualTo(
                jdbc.queryForObject("SELECT lease_token FROM paypal_webhook_retry_attempt_history "
                                + "WHERE event_id = ? AND attempt_number = 1",
                        String.class, eventId));
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM paypal_webhook_retry WHERE event_id = ?", Integer.class, eventId)).isZero();
        List<String> retryHistory = jdbc.query(
                "SELECT attempt_number || ':' || outcome FROM paypal_webhook_retry_attempt_history "
                        + "WHERE event_id = ? ORDER BY attempt_number",
                (rs, rowNum) -> rs.getString(1), eventId);
        assertThat(retryHistory).containsExactly("1:LEASE_EXPIRED", "2:PROCESSED");
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM paypal_webhook_retry_attempt_history "
                        + "WHERE event_id = ? AND outcome = 'PROCESSED'",
                Integer.class, eventId)).isEqualTo(1);

        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM payment_provider_webhook_event WHERE event_id = ?",
                Integer.class, eventId)).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT processing_status FROM payment_provider_webhook_event WHERE event_id = ?",
                String.class, eventId)).isEqualTo("PROCESSED");
        PaymentResponse captured = payments.findCheckout(checkoutId);
        assertThat(captured.state()).isEqualTo(PaymentState.CAPTURED);
        assertThat(captured.providerCaptureId()).isEqualTo(captureId);
        CheckoutResponse reconciled = checkoutPayments.find(checkoutId);
        assertThat(reconciled.state()).isEqualTo(BuyerCheckoutState.PAID);
        assertThat(reconciled.paymentState()).isEqualTo(PaymentState.CAPTURED);
        assertThat(reconciled.orders()).hasSize(1);
        var paidOrder = reconciled.orders().getFirst();
        assertThat(paidOrder.state()).isEqualTo(OrderState.PAID);
        assertThat(paidOrder.paymentState()).isEqualTo(PaymentState.CAPTURED);
        assertThat(paidOrder.inventoryState()).isEqualTo(ReservationStatus.CLAIMED);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM settlement_journal WHERE source_attempt_id = ?",
                Integer.class, captured.attemptId())).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM order_fulfillment WHERE order_id = ?",
                Integer.class, paidOrder.orderId())).isEqualTo(1);
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
        PaymentWebhookSignatureRequest signature = new PaymentWebhookSignatureRequest(
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

        PaymentWebhookEventResponse rejectedEvent = payments.verifyPayPalWebhook(signature, rawBody);
        assertThat(nextPayPalRequestAtPath("/v1/notifications/verify-webhook-signature")).isNotNull();
        PaymentWebhookResponse duplicate = payments.processVerifiedPayPalWebhook(rejectedEvent);
        assertThat(duplicate.duplicate()).isTrue();
        assertThat(payments.find(orderId).state()).isEqualTo(PaymentState.AUTHORIZED);
    }

    private void assertUnknownCaptureWebhookCanBeRetried() throws Exception {
        UUID orderId = UUID.randomUUID();
        PaymentResponse pending = payments.submitInitial(orderId, BigDecimal.valueOf(17200));
        PaymentResponse authorized = payments.completeApproval(orderId, pending.providerOrderId());
        String eventId = "WH-" + UUID.randomUUID();
        String captureId = "CAPTURE-WH-" + UUID.randomUUID();
        PaymentWebhookSignatureRequest signature = new PaymentWebhookSignatureRequest(
                "SHA256withRSA", "https://local.test/cert", "tx-local", "signature-local",
                "2026-11-09T00:00:00Z");
        String unmatchedRawBody = captureWebhook(
                eventId, authorized.providerOrderId() + "-unknown", captureId, "12.91", "USD");
        webhookService.handle(signature, unmatchedRawBody);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM paypal_webhook_retry WHERE event_id = ?", Integer.class, eventId)).isEqualTo(1);
        assertThat(nextPayPalRequestAtPath("/v1/notifications/verify-webhook-signature")).isNotNull();
        PaymentWebhookEventResponse unmatched = payments.verifyPayPalWebhook(signature, unmatchedRawBody);
        assertThat(nextPayPalRequestAtPath("/v1/notifications/verify-webhook-signature")).isNotNull();
        PaymentWebhookResponse retryable = payments.processVerifiedPayPalWebhook(unmatched);
        assertThat(retryable.accepted()).isFalse();
        assertThat(retryable.retryable()).isTrue();
        assertThat(retryable.duplicate()).isFalse();
        assertThat(payments.find(orderId)).usingRecursiveComparison().isEqualTo(authorized);

        jdbc.update("UPDATE payment_attempt SET provider_order_id = ? WHERE attempt_id = ?",
                authorized.providerOrderId() + "-unknown", authorized.attemptId());
        PayPalWebhookService retryWebhookService = new PayPalWebhookService(transactionManager, payments,
                mock(OrderService.class), mock(CheckoutPaymentService.class), mock(OrderRefundService.class),
                new PayPalWebhookRetryRepository(jdbc));
        new PayPalWebhookRetryWorker(
                new PayPalWebhookRetryRepository(jdbc), retryWebhookService, new SimpleMeterRegistry()).retryDueEvents();
        assertThat(payments.find(orderId).state()).isEqualTo(PaymentState.CAPTURED);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM paypal_webhook_retry WHERE event_id = ?", Integer.class, eventId)).isZero();
    }


    @Test
    void captureReservationArbitratesAcrossServiceInstancesAndProtectsSuccessFromStaleRetry() throws Exception {
        UUID orderId = UUID.randomUUID();
        BigDecimal amountWon = BigDecimal.valueOf(12900);
        BarrierPaymentProvider barrier = new BarrierPaymentProvider(paymentProvider);
        PaymentServiceImpl first = paymentService(barrier);
        PaymentServiceImpl second = paymentService(barrier);
        ExecutorService executor = Executors.newFixedThreadPool(3);
        try {
            PaymentResponse pending = first.submitInitial(orderId, amountWon);
            assertThat(jdbc.queryForObject("""
                    SELECT status FROM payment_recovery_job WHERE payment_attempt_id = ?
                    """, String.class, pending.attemptId())).isEqualTo("PENDING");
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
        public PaymentProviderResponse perform(PaymentRequest request) {
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
        public PaymentProviderResponse completeApproval(PaymentRequest request, String returnedProviderOrderId) {
            return delegate.completeApproval(request, returnedProviderOrderId);
        }

        @Override
        public PaymentRefundProviderResponse refund(PaymentRefundRequest request) {
            return delegate.refund(request);
        }

        @Override
        public PaymentRefundProviderResponse getRefund(String refundId) {
            return delegate.getRefund(refundId);
        }

        @Override
        public PaymentWebhookEventResponse verifyWebhook(PaymentWebhookSignatureRequest signature, String rawBody) {
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
        public PaymentProviderResponse perform(PaymentRequest request) {
            if (request.operation() != PaymentOperation.CAPTURE) return delegate.perform(request);
            captureCalls.incrementAndGet();
            captureStarted.countDown();
            BarrierPaymentProvider.await(releaseCapture);
            return new PaymentProviderResponse(PaymentProviderResponse.Outcome.CAPTURED,
                    request.providerOrderId(), request.providerAuthorizationId(), captureId, null);
        }

        @Override
        public PaymentProviderResponse completeApproval(PaymentRequest request, String returnedProviderOrderId) {
            return delegate.completeApproval(request, returnedProviderOrderId);
        }

        @Override
        public PaymentRefundProviderResponse refund(PaymentRefundRequest request) {
            return delegate.refund(request);
        }

        @Override
        public PaymentRefundProviderResponse getRefund(String refundId) {
            return delegate.getRefund(refundId);
        }

        @Override
        public PaymentWebhookEventResponse verifyWebhook(PaymentWebhookSignatureRequest signature, String rawBody) {
            return delegate.verifyWebhook(signature, rawBody);
        }
    }

    private static final class ObservedPaymentService extends PaymentServiceImpl {
        private final CountDownLatch webhookLockAttempted;
        private final AtomicReference<Thread> webhookLockThread;

        private ObservedPaymentService(PaymentAttemptRepository attempts, PaymentRecoveryRepository recovery,
                PlatformTransactionManager transactionManager, PaymentProvider provider,
                FxConversionService fxConversionService, OwnerLockRegistry locks,
                PaymentRefundWorkflow refundWorkflow, PaymentWebhookProcessor webhookProcessor, JdkIdGenerator ids,
                String publicBaseUrl, CountDownLatch webhookLockAttempted,
                AtomicReference<Thread> webhookLockThread) {
            super(attempts, recovery, transactionManager, provider, fxConversionService, locks, refundWorkflow,
                    webhookProcessor, ids, publicBaseUrl, Duration.ofSeconds(30), Duration.ofMinutes(30));
            this.webhookLockAttempted = webhookLockAttempted;
            this.webhookLockThread = webhookLockThread;
        }

        @Override
        public <T> T withPayPalWebhookOwnerLock(PaymentWebhookEventResponse event, Supplier<T> action) {
            webhookLockThread.set(Thread.currentThread());
            webhookLockAttempted.countDown();
            return super.withPayPalWebhookOwnerLock(event, action);
        }
    }
    @Test
    void paypalWebhookRetryQueueClaimsAcrossInstancesAndRetainsExhaustion() throws Exception {
        PayPalWebhookRetryRepository retryRepository =
                new PayPalWebhookRetryRepository(jdbc);
        String eventId = "retry-integration-" + UUID.randomUUID();
        PaymentWebhookEventResponse event = new PaymentWebhookEventResponse(eventId, "PAYMENT.CAPTURE.COMPLETED",
                "capture-" + UUID.randomUUID(), "COMPLETED", "unmatched-order", null,
                new BigDecimal("12.91"), "USD");
        retryRepository.enqueue(retryEvent(event));

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Callable<List<ClaimedRetry>> claim = () -> {
                ready.countDown();
                start.await();
                return retryRepository.claimDue();
            };
            Future<?> first = pool.submit(claim);
            Future<?> second = pool.submit(claim);
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            int claims = ((List<?>) first.get()).size() + ((List<?>) second.get()).size();
            assertThat(claims).isEqualTo(1);

            ClaimedRetry claimed =
                    retryRepository.claimDue().stream().findFirst().orElse(null);
            assertThat(claimed).isNull();
            String leaseOwner = jdbc.queryForObject(
                    "SELECT lease_owner FROM paypal_webhook_retry WHERE event_id = ?", String.class, eventId);
            retryRepository.reschedule(eventId, 1, leaseOwner,
                    PayPalWebhookRetryRepository.RetryFailure.CORRELATION_MISS, "Still unmatched");
            assertThat(jdbc.queryForObject(
                    "SELECT next_attempt_at > CURRENT_TIMESTAMP FROM paypal_webhook_retry WHERE event_id = ?",
                    Boolean.class, eventId)).isTrue();
            jdbc.update("UPDATE paypal_webhook_retry SET next_attempt_at = CURRENT_TIMESTAMP WHERE event_id = ?",
                    eventId);
            claimed = retryRepository.claimDue().get(0);
            assertThat(claimed.attempt()).isEqualTo(2);
            jdbc.update("""
                    UPDATE paypal_webhook_retry
                    SET attempt_count = 7, next_attempt_at = CURRENT_TIMESTAMP,
                        lease_owner = NULL, lease_until = NULL
                    WHERE event_id = ?
                    """, eventId);
            claimed = retryRepository.claimDue().get(0);
            assertThat(claimed.attempt()).isEqualTo(8);
            retryRepository.reschedule(eventId, claimed.attempt(), claimed.leaseOwner(),
                    PayPalWebhookRetryRepository.RetryFailure.CORRELATION_MISS, "Attempt limit reached");
            assertThat(jdbc.queryForObject(
                    "SELECT state FROM paypal_webhook_retry WHERE event_id = ?", String.class, eventId))
                    .isEqualTo("EXHAUSTED");
            assertThat(jdbc.queryForObject(
                    "SELECT last_error FROM paypal_webhook_retry WHERE event_id = ?", String.class, eventId))
                    .isEqualTo("Attempt limit reached");
            assertThat(retryRepository.claimDue()).isEmpty();
            String crashedEventId = "retry-crashed-" + UUID.randomUUID();
            retryRepository.enqueue(new WebhookRetryEvent(crashedEventId, "PAYMENT.CAPTURE.COMPLETED",
                    "capture-" + UUID.randomUUID(), "COMPLETED", "unmatched-order", null,
                    new BigDecimal("12.91"), "USD"));
            jdbc.update("""
                    UPDATE paypal_webhook_retry
                    SET attempt_count = 8, next_attempt_at = CURRENT_TIMESTAMP,
                        lease_owner = 'crashed-worker',
                        lease_until = CURRENT_TIMESTAMP - INTERVAL '1 second'
                    WHERE event_id = ?
                    """, crashedEventId);
            assertThat(retryRepository.claimDue()).isEmpty();
            assertThat(jdbc.queryForObject(
                    "SELECT state FROM paypal_webhook_retry WHERE event_id = ?", String.class, crashedEventId))
                    .isEqualTo("EXHAUSTED");
            assertThat(jdbc.queryForObject(
                    "SELECT finished_at IS NOT NULL FROM paypal_webhook_retry WHERE event_id = ?",
                    Boolean.class, crashedEventId)).isTrue();
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void webhookOperatorIdempotencyAndAuditSurviveQueueDeletion() {
        String eventId = "operator-audit-" + UUID.randomUUID();
        String key = "operator-key-" + UUID.randomUUID();
        insertExhaustedRetry(eventId);

        assertThat(webhookOperator.requeue(eventId, key, "operator-a", "reviewed delivery").outcome())
                .isEqualTo("REQUEUED");
        jdbc.update("DELETE FROM paypal_webhook_retry WHERE event_id = ?", eventId);
        insertExhaustedRetry(eventId);
        assertThat(webhookOperator.requeue(eventId, key, "operator-a", "reviewed delivery").outcome())
                .isEqualTo("REQUEUED");
        assertConflict(() -> webhookOperator.requeue(eventId, key, "operator-b", "reviewed delivery"));
        assertConflict(() -> webhookOperator.requeue(eventId, key, "operator-a", "different reason"));
        assertConflict(() -> webhookOperator.requeue("different-event", key, "operator-a", "reviewed delivery"));
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM paypal_webhook_retry_operator_audit WHERE event_id = ?",
                Integer.class, eventId)).isEqualTo(1);

        assertThatThrownBy(() -> jdbc.update(
                "UPDATE paypal_webhook_retry_operator_audit SET reason = 'changed' WHERE idempotency_key = ?", key))
                .isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> jdbc.update(
                "DELETE FROM paypal_webhook_retry_operator_audit WHERE idempotency_key = ?", key))
                .isInstanceOf(DataAccessException.class);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM paypal_webhook_retry_operator_audit WHERE idempotency_key = ?",
                Integer.class, key)).isEqualTo(1);
    }

    @Test
    void concurrentDistinctRequeuesSerializeOnExhaustedEvent() throws Exception {
        String eventId = "operator-race-" + UUID.randomUUID();
        insertExhaustedRetry(eventId);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        Callable<Boolean> first = requeueAfterBarrier(eventId, "race-key-a-" + UUID.randomUUID(), ready, start);
        Callable<Boolean> second = requeueAfterBarrier(eventId, "race-key-b-" + UUID.randomUUID(), ready, start);
        try {
            Future<Boolean> firstResult = pool.submit(first);
            Future<Boolean> secondResult = pool.submit(second);
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            int successes = (firstResult.get(10, TimeUnit.SECONDS) ? 1 : 0)
                    + (secondResult.get(10, TimeUnit.SECONDS) ? 1 : 0);
            assertThat(successes).isEqualTo(1);
            assertThat(jdbc.queryForObject(
                    "SELECT COUNT(*) FROM paypal_webhook_retry_operator_audit WHERE event_id = ?",
                    Integer.class, eventId)).isEqualTo(1);
        } finally {
            pool.shutdownNow();
                jdbc.update("DELETE FROM paypal_webhook_retry WHERE event_id = ?", eventId);
        }
    }

    @Test
    void concurrentSameKeyReplaysOneDurableOutcome() throws Exception {
        String eventId = "operator-same-key-" + UUID.randomUUID();
        String key = "operator-idempotency-" + UUID.randomUUID();
        insertExhaustedRetry(eventId);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        Callable<Boolean> first = requeueAfterBarrier(eventId, key, ready, start);
        Callable<Boolean> second = requeueAfterBarrier(eventId, key, ready, start);
        try {
            Future<Boolean> firstResult = pool.submit(first);
            Future<Boolean> secondResult = pool.submit(second);
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            assertThat(firstResult.get(10, TimeUnit.SECONDS)).isTrue();
            assertThat(secondResult.get(10, TimeUnit.SECONDS)).isTrue();
            assertThat(jdbc.queryForObject(
                    "SELECT COUNT(*) FROM paypal_webhook_retry_operator_audit WHERE idempotency_key = ?",
                    Integer.class, key)).isEqualTo(1);
        } finally {
            pool.shutdownNow();
                jdbc.update("DELETE FROM paypal_webhook_retry WHERE event_id = ?", eventId);
        }
    }

    @Test
    void threeRequeueLimitSurvivesWorkerDeletionAndQueueRecreation() {
        String eventId = "operator-cap-" + UUID.randomUUID();
        for (int attempt = 1; attempt <= 3; attempt++) {
            insertExhaustedRetry(eventId);
            assertThat(webhookOperator.requeue(eventId, "cap-key-" + attempt + "-" + UUID.randomUUID(),
                    "operator-a", "approved retry " + attempt).outcome()).isEqualTo("REQUEUED");
            jdbc.update("DELETE FROM paypal_webhook_retry WHERE event_id = ?", eventId);
        }
        insertExhaustedRetry(eventId);
        assertConflict(() -> webhookOperator.requeue(eventId, "cap-key-four-" + UUID.randomUUID(),
                "operator-a", "must be blocked"));
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM paypal_webhook_retry_operator_audit WHERE event_id = ?",
                Integer.class, eventId)).isEqualTo(3);
    }

    private Callable<Boolean> requeueAfterBarrier(
            String eventId, String key, CountDownLatch ready, CountDownLatch start) {
        return () -> {
            ready.countDown();
            start.await();
            try {
                webhookOperator.requeue(eventId, key, "race-operator", "concurrent review");
                return true;
            } catch (ApplicationException conflict) {
                return false;
            }
        };
    }

    private void insertExhaustedRetry(String eventId) {
        jdbc.update("""
                INSERT INTO paypal_webhook_retry
                    (event_id, event_type, attempt_count, state, created_at, next_attempt_at, finished_at)
                VALUES (?, 'PAYMENT.CAPTURE.COMPLETED', 8, 'EXHAUSTED',
                    CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """, eventId);
    }

    private void assertConflict(Runnable request) {
        assertThatThrownBy(request::run)
                .isInstanceOfSatisfying(ApplicationException.class,
                        error -> assertThat(error.code()).isEqualTo(Code.CONFLICT));
    }


    private static WebhookRetryEvent retryEvent(PaymentWebhookEventResponse e) {
        return new WebhookRetryEvent(e.eventId(), e.eventType(), e.resourceId(), e.resourceStatus(),
                e.providerOrderId(), e.relatedCaptureId(), e.resourceAmount(), e.resourceCurrency());
    }

    private PaymentServiceImpl observedPaymentService(PaymentProvider provider, CountDownLatch webhookLockAttempted,
            AtomicReference<Thread> webhookLockThread) {
        OwnerLockRegistry locks = new OwnerLockRegistry();
        JdkIdGenerator ids = new JdkIdGenerator();
        TransactionTemplate transactions = new TransactionTemplate(transactionManager);
        Duration recoveryDelay = Duration.ofSeconds(30);
        PaymentRefundWorkflow refundWorkflow = new PaymentRefundWorkflow(locks, paymentAttempts, paymentRefunds,
                paymentRecovery, transactions, provider, recoveryDelay, ids);
        PaymentWebhookProcessor webhookProcessor = new PaymentWebhookProcessor(locks, paymentAttempts,
                paymentRefunds, paymentWebhookEvents, paymentRecovery, transactions, provider);
        return new ObservedPaymentService(paymentAttempts, paymentRecovery, transactionManager, provider,
                fxConversionService, locks, refundWorkflow, webhookProcessor, ids, publicBaseUrl,
                webhookLockAttempted, webhookLockThread);
    }

    private PaymentServiceImpl paymentService(PaymentProvider provider) {
        OwnerLockRegistry locks = new OwnerLockRegistry();
        JdkIdGenerator ids = new JdkIdGenerator();
        TransactionTemplate transactions = new TransactionTemplate(transactionManager);
        Duration recoveryDelay = Duration.ofSeconds(30);
        PaymentRefundWorkflow refundWorkflow = new PaymentRefundWorkflow(locks, paymentAttempts, paymentRefunds,
                paymentRecovery, transactions, provider, recoveryDelay, ids);
        PaymentWebhookProcessor webhookProcessor = new PaymentWebhookProcessor(locks, paymentAttempts,
                paymentRefunds, paymentWebhookEvents, paymentRecovery, transactions, provider);
        return new PaymentServiceImpl(paymentAttempts, paymentRecovery, transactionManager, provider,
                fxConversionService, locks, refundWorkflow, webhookProcessor, ids, publicBaseUrl, recoveryDelay,
                Duration.ofMinutes(30));
    }
}
