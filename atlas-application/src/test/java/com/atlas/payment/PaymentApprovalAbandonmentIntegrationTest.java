package com.atlas.payment;

import static org.assertj.core.api.Assertions.assertThat;

import com.atlas.order.dto.response.OrderViewResponse;
import com.atlas.inventory.dto.response.InventoryResponse;
import com.atlas.catalog.dto.request.ProductRequest;
import com.atlas.application.service.PaymentRecoveryWorker;
import com.atlas.order.service.CartService;
import com.atlas.catalog.service.ProductService;
import com.atlas.catalog.service.SellerService;
import com.atlas.inventory.service.InventoryService;
import com.atlas.order.dto.response.CheckoutResponse;
import com.atlas.order.model.BuyerCheckoutState;
import com.atlas.order.model.OrderState;
import com.atlas.order.service.CartCheckoutService;
import com.atlas.order.service.CheckoutPaymentService;
import com.atlas.order.service.OrderRefundService;
import com.atlas.order.service.OrderService;
import com.atlas.payment.dto.response.PaymentResponse;
import com.atlas.payment.model.PaymentRecoveryJobStatus;
import com.atlas.payment.model.PaymentState;
import com.atlas.payment.service.PaymentRecoveryService;
import com.atlas.payment.service.PaymentService;
import com.atlas.support.AppendOnlyTestReset;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.sql.Timestamp;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * 구매자가 PayPal 승인 화면에서 이탈한 경우를 재현한다. 콜백은 오지 않고, worker만 남는다.
 *
 * <p>기대 동작: 승인 deadline이 지나면 worker가 provider에 실제 상태를 확인하고, 아직 승인 전이면
 * 결제를 닫아 재고 예약을 해제한다. capture가 이미 확정된 경우에는 재고를 해제하지 않고 정상
 * 결제로 수렴한다.
 */
@Testcontainers
@SpringBootTest
class PaymentApprovalAbandonmentIntegrationTest extends PayPalIntegrationTestSupport {
    @Container
    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18.6")
            .withDatabaseName("atlas");

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry properties) {
        properties.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        properties.add("spring.datasource.username", POSTGRES::getUsername);
        properties.add("spring.datasource.password", POSTGRES::getPassword);
        paypalTestProperties(properties);
        properties.add("atlas.admission.expiry-poll-interval", () -> "PT24H");
        properties.add("atlas.orders.expiry-poll-interval", () -> "PT24H");
        properties.add("atlas.payment.paypal.webhook-retry.worker.enabled", () -> "false");
        properties.add("atlas.payment.recovery.worker.enabled", () -> "false");
        properties.add("atlas.payment.recovery.initial-delay", () -> "PT0S");
        properties.add("atlas.payment.approval-ttl", () -> "PT30M");
    }

    @Autowired private PaymentService payments;
    @Autowired private PaymentRecoveryService recovery;
    @Autowired private OrderService orders;
    @Autowired private CheckoutPaymentService checkouts;
    @Autowired private OrderRefundService refunds;
    @Autowired private CartCheckoutService cartCheckouts;
    @Autowired private CartService carts;
    @Autowired private SellerService sellers;
    @Autowired private ProductService products;
    @Autowired private InventoryService inventory;
    @Autowired private JdbcTemplate jdbc;

    private PaymentRecoveryWorker worker;

    @BeforeEach
    void setUp() {
        AppendOnlyTestReset.clear(jdbc, "payment_recovery_operator_action");
        jdbc.update("DELETE FROM payment_recovery_job");
        worker = new PaymentRecoveryWorker(
                recovery, payments, orders, checkouts, refunds, new SimpleMeterRegistry(), 10,
                Duration.ofSeconds(60), 8, 8, Duration.ofHours(24), Duration.ofSeconds(1),
                Duration.ofMinutes(15));
    }

    @Test
    void abandonedApprovalClosesPaymentAndReleasesInventoryAfterTheDeadline() {
        Fixture fixture = awaitingApproval();
        assertThat(job(fixture.attemptId()))
                .containsEntry("status", PaymentRecoveryJobStatus.WAITING_FOR_BUYER.name());
        assertThat(inventory.find(fixture.sellerId(), fixture.skuId()).reserved()).isEqualTo(1);

        expireDeadline(fixture.attemptId());
        // 구매자는 아무 신호도 보내지 않는다. worker만 반복 실행된다. provider 조회 결과도
        // 여전히 payer 승인 대기다.
        providerStillAwaitsPayer();
        pollUntilDone();

        assertThat(payments.findCheckout(fixture.checkoutId()).state()).isEqualTo(PaymentState.VOIDED);
        assertThat(jdbc.queryForObject(
                "SELECT state FROM buyer_checkout WHERE checkout_id = ?",
                String.class, fixture.checkoutId())).isEqualTo(BuyerCheckoutState.FAILED.name());
        assertThat(jdbc.queryForObject(
                "SELECT state FROM purchase_order WHERE order_id = ?",
                String.class, fixture.orderId())).isEqualTo(OrderState.FAILED.name());
        InventoryResponse.Pool pool = inventory.find(fixture.sellerId(), fixture.skuId());
        assertThat(pool.available()).isEqualTo(1);
        assertThat(pool.reserved()).isZero();
        assertThat(pool.claimed()).isZero();
        assertThat(job(fixture.attemptId()))
                .containsEntry("status", PaymentRecoveryJobStatus.COMPLETED.name());
    }

    @Test
    void lateApprovalAfterTheDeadlineStillConvergesToCaptureWithoutReleasingInventory() {
        Fixture fixture = awaitingApproval();
        expireDeadline(fixture.attemptId());

        // deadline 경과 후 구매자가 승인하고 PayPal이 실제로 capture까지 완료한 경우.
        // provider 조회가 승인 상태를 돌려주므로, 결제를 닫아버리면 자금 이동을 놓친다.
        queueOrderStatus("APPROVED");
        queueOrderAuthorizationStatus("CREATED");
        pollUntilDone();

        assertThat(captureRequestCount()).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT state FROM buyer_checkout WHERE checkout_id = ?",
                String.class, fixture.checkoutId())).isEqualTo(BuyerCheckoutState.PAID.name());
        assertThat(jdbc.queryForObject(
                "SELECT state FROM purchase_order WHERE order_id = ?",
                String.class, fixture.orderId())).isEqualTo(OrderState.PAID.name());
        InventoryResponse.Pool pool = inventory.find(fixture.sellerId(), fixture.skuId());
        assertThat(pool.claimed()).isEqualTo(1);
        assertThat(pool.reserved()).isZero();
        assertThat(settlementJournalCount(fixture.checkoutId())).isEqualTo(1);
    }

    @Test
    void approvalBeforeTheDeadlineIsNotClosedByTheWorker() {
        Fixture fixture = awaitingApproval();

        pollUntilDone();

        assertThat(payments.findCheckout(fixture.checkoutId()).state())
                .isEqualTo(PaymentState.AUTHORIZATION_PENDING);
        assertThat(job(fixture.attemptId()))
                .containsEntry("status", PaymentRecoveryJobStatus.WAITING_FOR_BUYER.name());
        assertThat(inventory.find(fixture.sellerId(), fixture.skuId()).reserved()).isEqualTo(1);
        assertThat(captureRequestCount()).isZero();
    }

    @Test
    void aParkedApprovalWaitWithoutADeadlineIsStillResumed() {
        Fixture fixture = awaitingApproval();
        assertThat(job(fixture.attemptId()))
                .containsEntry("status", PaymentRecoveryJobStatus.WAITING_FOR_BUYER.name());

        // 과거 데이터나 다른 경로가 deadline 없이 대기 상태를 만들 수 있다. 그런 행은 재개
        // sweep의 조건(deadline 경과)에 걸리지 않아 영원히 남는다. NULL은 즉시 확정 대상이다.
        jdbc.update("UPDATE payment_attempt SET approval_expires_at = NULL WHERE attempt_id = ?",
                fixture.attemptId());
        providerStillAwaitsPayer();
        pollUntilDone();

        assertThat(payments.findCheckout(fixture.checkoutId()).state()).isEqualTo(PaymentState.VOIDED);
        assertThat(jdbc.queryForObject(
                "SELECT state FROM buyer_checkout WHERE checkout_id = ?",
                String.class, fixture.checkoutId())).isEqualTo(BuyerCheckoutState.FAILED.name());
        assertThat(inventory.find(fixture.sellerId(), fixture.skuId()).reserved()).isZero();
        assertThat(job(fixture.attemptId()))
                .containsEntry("status", PaymentRecoveryJobStatus.COMPLETED.name());
    }

    @Test
    void parkedApprovalWaitAlwaysCarriesADeadline() {
        Fixture fixture = awaitingApproval();

        // 대기 상태로 넘어간 결제는 반드시 deadline을 가져야, 재개 sweep이 확정할 수 있다.
        assertThat(jdbc.queryForObject(
                "SELECT approval_expires_at FROM payment_attempt WHERE attempt_id = ?",
                Timestamp.class, fixture.attemptId())).isNotNull();
        assertThat(job(fixture.attemptId()))
                .containsEntry("status", PaymentRecoveryJobStatus.WAITING_FOR_BUYER.name());
    }

    private record Fixture(UUID sellerId, String skuId, UUID checkoutId, UUID orderId, UUID attemptId) { }

    private Fixture awaitingApproval() {
        UUID sellerId = sellers.createSeller().sellerId();
        var product = products.createProduct(sellerId, new ProductRequest.CreateProduct("Approval abandonment", null));
        String skuId = "approval-abandon-" + UUID.randomUUID();
        products.registerSku(sellerId, product.productId(), new ProductRequest.RegisterSellerSku(skuId, 17_200, 1));
        String buyerRef = "approval-abandon-buyer-" + UUID.randomUUID();
        carts.setItemQuantity(sellerId, buyerRef, skuId, 1);
        CheckoutResponse reserved = cartCheckouts.checkout(buyerRef, "approval-abandon-" + UUID.randomUUID());

        // 주문 생성 직후 worker는 provider를 호출하지 않고 승인 대기로 넘긴다
        // (approvalUrl이 이미 있으므로 getOrder가 필요 없다). 따라서 여기서는
        // provider 상태 queue를 소비하지 않는다.
        CheckoutResponse pending = checkouts.submit(reserved.checkoutId());
        assertThat(pending.paymentState()).isEqualTo(PaymentState.AUTHORIZATION_PENDING);
        PaymentResponse attempt = payments.findCheckout(reserved.checkoutId());
        assertThat(attempt.approvalExpiresAt()).isNotNull();

        worker.poll();
        return new Fixture(sellerId, skuId, reserved.checkoutId(),
                reserved.orders().getFirst().orderId(), attempt.attemptId());
    }

    /** PayPal order가 아직 payer 승인을 기다리는 상태를 흉내낸다. */
    private void providerStillAwaitsPayer() {
        queueOrderStatus("PAYER_ACTION_REQUIRED");
    }

    private void expireDeadline(UUID attemptId) {
        jdbc.update("""
                UPDATE payment_attempt SET approval_expires_at = CURRENT_TIMESTAMP - INTERVAL '1 second'
                WHERE attempt_id = ?
                """, attemptId);
    }

    private Map<String, Object> job(UUID attemptId) {
        var rows = jdbc.queryForList("""
                SELECT status, work_stage FROM payment_recovery_job WHERE payment_attempt_id = ?
                """, attemptId);
        assertThat(rows).as("recovery job for attempt").hasSize(1);
        return rows.getFirst();
    }

    private int settlementJournalCount(UUID checkoutId) {
        return jdbc.queryForObject("""
                SELECT COUNT(*) FROM settlement_journal WHERE checkout_id = ?
                """, Integer.class, checkoutId);
    }

    private void pollUntilDone() {
        for (int attempt = 0; attempt < 8; attempt++) {
            worker.poll();
        }
    }
}
