package com.atlas.payment;

import static org.assertj.core.api.Assertions.assertThat;

import com.atlas.application.service.PaymentRecoveryWorker;
import com.atlas.catalog.dto.request.ProductRequest;
import com.atlas.catalog.service.ProductService;
import com.atlas.catalog.service.SellerService;
import com.atlas.inventory.dto.response.InventoryResponse;
import com.atlas.inventory.service.InventoryService;
import com.atlas.order.dto.response.CheckoutResponse;
import com.atlas.order.model.OrderState;
import com.atlas.order.service.CartCheckoutService;
import com.atlas.order.service.CartService;
import com.atlas.order.service.CheckoutPaymentService;
import com.atlas.order.service.OrderRefundService;
import com.atlas.order.service.OrderService;
import com.atlas.payment.dto.response.PaymentResponse;
import com.atlas.payment.model.PaymentRecoveryJobStatus;
import com.atlas.payment.model.PaymentState;
import com.atlas.payment.persistence.projection.PaymentRecoveryReviewRow;
import com.atlas.payment.repository.PaymentRecoveryRepository;
import com.atlas.payment.service.PaymentRecoveryService;
import com.atlas.payment.service.PaymentService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * PayPal 4xx 거절의 분류(ADR-0079)를 실제 PostgreSQL 과 로컬 PayPal 대역으로 확인한다.
 *
 * <p>핵심 불변식: 응답이 "작업이 일어나지 않았음"을 증명하는 거절만 재시도 없이 종결 상태에 한 번 도달하고,
 * 그 밖의 거절과 장애는 이전처럼 결과 불명(UNKNOWN)으로 남아 같은 Request-Id 로 복구된다.
 */
@Testcontainers
@SpringBootTest
class PayPalDefinitiveRejectionIntegrationTest extends PayPalIntegrationTestSupport {
    private static final String CREATE_ORDER_CALL = "POST /v2/checkout/orders";

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
        properties.add("atlas.payment.recovery.initial-delay", () -> "PT0S");
    }

    @Autowired
    private PaymentService payments;
    @Autowired
    private PaymentRecoveryRepository paymentRecovery;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private PaymentRecoveryService recovery;
    @Autowired
    private OrderService orders;
    @Autowired
    private CheckoutPaymentService checkouts;
    @Autowired
    private OrderRefundService refunds;
    @Autowired
    private CartCheckoutService cartCheckouts;
    @Autowired
    private CartService carts;
    @Autowired
    private SellerService sellers;
    @Autowired
    private ProductService products;
    @Autowired
    private InventoryService inventory;

    private PaymentRecoveryWorker worker;

    @BeforeEach
    void setUp() {
        jdbc.update("DELETE FROM payment_recovery_operator_action");
        jdbc.update("DELETE FROM payment_recovery_job");
        // 재시도 간격만 줄인 실제 worker. 최대 시도 8회, 24시간 window 는 운영 값 그대로다.
        worker = new PaymentRecoveryWorker(recovery, payments, orders, checkouts, refunds,
                new SimpleMeterRegistry(), 10, Duration.ofSeconds(60), 8, Duration.ofHours(24),
                Duration.ofMillis(1), Duration.ofMillis(5));
    }

    @Test
    void definitivelyRejectedOrderCreationFailsTheOrderOnceAndReleasesInventory() {
        // 거절을 여러 번 넣어 둔다. 분류가 없으면 worker 가 최대 시도 횟수까지 같은 거절을 다시 받는다.
        for (int i = 0; i < 10; i++) queueRejection("create-order", 422, "DECIMALS_NOT_SUPPORTED");
        Fixture fixture = reservedCheckout();

        checkouts.submit(fixture.checkoutId());
        pollWorker();

        UUID attemptId = payments.findCheckout(fixture.checkoutId()).attemptId();
        assertThat(callCount(CREATE_ORDER_CALL)).isEqualTo(1);
        assertThat(payments.findCheckout(fixture.checkoutId()).state()).isEqualTo(PaymentState.DECLINED);
        assertThat(orderState(fixture.orderId())).isEqualTo(OrderState.FAILED.name());
        InventoryResponse.Pool pool = inventory.find(fixture.sellerId(), fixture.skuId());
        assertThat(pool.available()).isEqualTo(1);
        assertThat(pool.reserved()).isZero();
        assertThat(pool.claimed()).isZero();
        assertThat(jobStatus(attemptId)).isEqualTo(PaymentRecoveryJobStatus.COMPLETED.name());
    }

    @Test
    void expiredAuthorizationAtCaptureFailsTheOrderOnceAndReleasesInventory() {
        Fixture fixture = reservedCheckout();
        checkouts.submit(fixture.checkoutId());
        UUID attemptId = payments.findCheckout(fixture.checkoutId()).attemptId();
        // 구매자가 승인했고 authorize 는 성공하지만, 그 사이 PayPal 이 authorization 을 void·만료한 경우.
        // worker 는 주문 조회(승인됨)로 시작해 authorize 와 capture 를 보내고, capture 거절 뒤 주문을 다시 조회한다.
        jdbc.update("UPDATE payment_attempt SET approval_expires_at = CURRENT_TIMESTAMP - INTERVAL '1 second'"
                + " WHERE attempt_id = ?", attemptId);
        queueOrderStatus("APPROVED");
        queueOrderStatus("COMPLETED");
        queueOrderAuthorizationStatus("CREATED");
        queueOrderAuthorizationStatus("VOIDED");
        queueOrderCaptureStatus("NONE");
        queueOrderCaptureStatus("NONE");
        queueRejection("capture", 422, "AUTHORIZATION_VOIDED");

        pollWorker();

        assertThat(callCountMatching("POST /v2/payments/authorizations/.+/capture")).isEqualTo(1);
        assertThat(payments.findCheckout(fixture.checkoutId()).state()).isEqualTo(PaymentState.VOIDED);
        assertThat(orderState(fixture.orderId())).isEqualTo(OrderState.FAILED.name());
        InventoryResponse.Pool pool = inventory.find(fixture.sellerId(), fixture.skuId());
        assertThat(pool.available()).isEqualTo(1);
        assertThat(pool.reserved()).isZero();
        assertThat(pool.claimed()).isZero();
        assertThat(jobStatus(attemptId)).isEqualTo(PaymentRecoveryJobStatus.COMPLETED.name());
    }

    @Test
    void throttledOrderCreationStaysUnknownAndRecoversWithTheSameRequestId() throws Exception {
        UUID orderId = UUID.randomUUID();
        queueRejection("create-order", 429, "RATE_LIMIT_REACHED");

        PaymentResponse submitted = payments.submitInitial(orderId, BigDecimal.valueOf(17200));
        PaymentResponse recovered = payments.reconcile(orderId, "throttled-" + orderId);

        assertThat(submitted.state()).isEqualTo(PaymentState.UNKNOWN);
        assertThat(recovered.state()).isEqualTo(PaymentState.AUTHORIZATION_PENDING);
        assertThat(recovered.providerOrderId()).isNotNull();
        List<RecordedRequest> creations = drainRequestsAt("/v2/checkout/orders");
        assertThat(creations).hasSize(2);
        assertThat(creations.get(1).getHeader("PayPal-Request-Id"))
                .isEqualTo(creations.get(0).getHeader("PayPal-Request-Id"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"AUTHORIZATION_VOIDED", "AUTHORIZATION_EXPIRED"})
    void captureRejectedAsUnusableAuthorizationEndsVoidedOnceAfterOrderCheck(String issue) {
        UUID orderId = authorizedPayment();
        queueRejection("capture", 422, issue);
        // 거절 뒤 provider 가 주문을 조회한다: capture 없이 authorization 만 있는 주문.
        queueOrderStatus("COMPLETED");
        queueOrderAuthorizationStatus("AUTHORIZATION_VOIDED".equals(issue) ? "VOIDED" : "CREATED");
        queueOrderCaptureStatus("NONE");

        PaymentResponse first = payments.reconcile(orderId, "unusable-" + orderId);
        PaymentResponse second = payments.reconcile(orderId, "unusable-again-" + orderId);

        assertThat(first.state()).isEqualTo(PaymentState.VOIDED);
        assertThat(first.authorizationActive()).isFalse();
        assertThat(second.state()).isEqualTo(PaymentState.VOIDED);
        assertThat(callCountMatching("POST /v2/payments/authorizations/.+/capture")).isEqualTo(1);
    }

    @Test
    void captureRejectedButOrderShowsACaptureEndsCaptured() {
        UUID orderId = authorizedPayment();
        queueRejection("capture", 422, "AUTHORIZATION_VOIDED");
        queueOrderStatus("COMPLETED");

        PaymentResponse result = payments.reconcile(orderId, "raced-" + orderId);

        assertThat(result.state()).isEqualTo(PaymentState.CAPTURED);
    }

    @Test
    void ambiguousCaptureRejectionsStayUnknownAndRecover() {
        UUID alreadyCaptured = authorizedPayment();
        queueRejection("capture", 422, "AUTHORIZATION_ALREADY_CAPTURED");
        PaymentResponse ambiguous = payments.reconcile(alreadyCaptured, "ambiguous-" + alreadyCaptured);
        assertThat(ambiguous.state()).isEqualTo(PaymentState.UNKNOWN);
        assertThat(payments.reconcile(alreadyCaptured, "ambiguous-again-" + alreadyCaptured).state())
                .isEqualTo(PaymentState.CAPTURED);

        // 거절 본문은 void 라는데 주문 조회가 authorization 을 아직 CREATED 로 보여 주면 서로 어긋난다. 확정하지 않는다.
        UUID contradictory = authorizedPayment();
        queueRejection("capture", 422, "AUTHORIZATION_VOIDED");
        queueOrderStatus("COMPLETED");
        queueOrderAuthorizationStatus("CREATED");
        queueOrderCaptureStatus("NONE");
        PaymentResponse unconfirmed = payments.reconcile(contradictory, "contradiction-" + contradictory);
        assertThat(unconfirmed.state()).isEqualTo(PaymentState.UNKNOWN);
        assertThat(payments.reconcile(contradictory, "contradiction-again-" + contradictory).state())
                .isEqualTo(PaymentState.CAPTURED);
    }

    @Test
    void voidOfAnAlreadyVoidedAuthorizationEndsVoidedOnce() {
        UUID orderId = authorizedPayment();
        queueRejection("void", 422, "PREVIOUSLY_VOIDED");

        PaymentResponse voided = payments.voidAuthorization(orderId, "void-" + orderId);
        PaymentResponse again = payments.voidAuthorization(orderId, "void-again-" + orderId);

        assertThat(voided.state()).isEqualTo(PaymentState.VOIDED);
        assertThat(voided.authorizationActive()).isFalse();
        assertThat(again.state()).isEqualTo(PaymentState.VOIDED);
        assertThat(callCountMatching("POST /v2/payments/authorizations/.+/void")).isEqualTo(1);
    }

    @Test
    void authorizationExpirationIsRecordedAndShownInTheReviewList() {
        UUID orderId = authorizedPayment();

        UUID attemptId = jdbc.queryForObject(
                "SELECT attempt_id FROM payment_attempt WHERE order_id = ?", UUID.class, orderId);
        Instant recorded = jdbc.queryForObject(
                "SELECT authorization_expires_at FROM payment_attempt WHERE attempt_id = ?",
                Timestamp.class, attemptId).toInstant();
        assertThat(recorded).isEqualTo(Instant.parse(AUTHORIZATION_EXPIRATION));

        // 복구 한도를 넘긴 상태를 DB 로 만든다. worker 는 꺼져 있다.
        jdbc.update("UPDATE payment_recovery_job SET status = 'NEEDS_REVIEW' WHERE payment_attempt_id = ?",
                attemptId);
        List<PaymentRecoveryReviewRow> review = new ArrayList<>(paymentRecovery.listNeedsReview(100, 0));
        assertThat(review).filteredOn(row -> attemptId.equals(row.paymentAttemptId()))
                .singleElement()
                .extracting(PaymentRecoveryReviewRow::authorizationExpiresAt)
                .isEqualTo(Instant.parse(AUTHORIZATION_EXPIRATION));
    }

    private record Fixture(UUID sellerId, String skuId, UUID checkoutId, UUID orderId) { }

    /** 재고 1개를 예약한 체크아웃(결제 제출 전)을 만든다. */
    private Fixture reservedCheckout() {
        UUID sellerId = sellers.createSeller().sellerId();
        var product = products.createProduct(sellerId, new ProductRequest.CreateProduct("Definitive rejection", null));
        String skuId = "definitive-rejection-" + UUID.randomUUID();
        products.registerSku(sellerId, product.productId(), new ProductRequest.RegisterSellerSku(skuId, 17_200, 1));
        String buyerRef = "definitive-rejection-buyer-" + UUID.randomUUID();
        carts.setItemQuantity(sellerId, buyerRef, skuId, 1);
        CheckoutResponse reserved = cartCheckouts.checkout(buyerRef, "definitive-rejection-" + UUID.randomUUID());
        return new Fixture(sellerId, skuId, reserved.checkoutId(), reserved.orders().getFirst().orderId());
    }

    private String orderState(UUID orderId) {
        return jdbc.queryForObject("SELECT state FROM purchase_order WHERE order_id = ?", String.class, orderId);
    }

    private String jobStatus(UUID attemptId) {
        return jdbc.queryForObject(
                "SELECT status FROM payment_recovery_job WHERE payment_attempt_id = ?", String.class, attemptId);
    }

    /** 대기 시간이 1~5ms 라 한 번 돌리면 due 가 된 job 을 이어서 처리한다. 20회면 최대 시도 8회를 넘긴다. */
    private void pollWorker() {
        for (int poll = 0; poll < 20; poll++) {
            worker.poll();
        }
    }

    /** 승인과 authorize 까지 끝나 capture 만 남은 결제 시도(AUTHORIZED)를 만든다. */
    private UUID authorizedPayment() {
        UUID orderId = UUID.randomUUID();
        PaymentResponse pending = payments.submitInitial(orderId, BigDecimal.valueOf(17200));
        PaymentResponse authorized = payments.completeApproval(orderId, pending.providerOrderId());
        assertThat(authorized.state()).isEqualTo(PaymentState.AUTHORIZED);
        return orderId;
    }

    private int callCount(String key) {
        return providerCalls().getOrDefault(key, 0);
    }

    private int callCountMatching(String regex) {
        return providerCalls().entrySet().stream().filter(entry -> entry.getKey().matches(regex))
                .mapToInt(Map.Entry::getValue).sum();
    }

    private List<RecordedRequest> drainRequestsAt(String path) throws InterruptedException {
        List<RecordedRequest> matched = new ArrayList<>();
        RecordedRequest request;
        while ((request = takePayPalRequest()) != null) {
            if (request.getPath().equals(path)) matched.add(request);
        }
        return matched;
    }
}
