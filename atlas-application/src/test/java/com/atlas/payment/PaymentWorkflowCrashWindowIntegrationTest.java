package com.atlas.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.doThrow;

import com.atlas.order.dto.response.OrderViewResponse;
import com.atlas.order.dto.request.OrderRequest;
import com.atlas.inventory.dto.response.InventoryResponse;
import com.atlas.catalog.dto.request.ProductRequest;
import com.atlas.order.service.CartService;
import com.atlas.catalog.service.ProductService;
import com.atlas.catalog.service.SellerService;
import com.atlas.inventory.service.InventoryService;
import com.atlas.order.dto.response.CheckoutResponse;
import com.atlas.order.model.BuyerCheckoutState;
import com.atlas.order.model.OrderRefundPaymentStatus;
import com.atlas.order.model.OrderState;
import com.atlas.order.service.CartCheckoutService;
import com.atlas.order.service.CheckoutPaymentService;
import com.atlas.order.service.OrderRefundService;
import com.atlas.order.service.OrderService;
import com.atlas.application.service.PaymentRecoveryWorker;
import com.atlas.payment.dto.response.PaymentResponse;
import com.atlas.payment.model.PaymentRecoveryJobStatus;
import com.atlas.payment.model.PaymentRecoveryWorkStage;
import com.atlas.payment.model.PaymentState;
import com.atlas.payment.service.PaymentRecoveryService;
import com.atlas.payment.service.PaymentService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * 커밋 경계에서 프로세스가 종료된 두 지점을 실제 transaction 경계로 재현한다.
 *
 * <p>1) intent(주문/checkout 상태 + attempt + recovery job)는 commit됐지만 PayPal 호출 전에 종료.
 * 2) PayPal capture는 durable하게 저장됐지만 owner 적용 전에 종료.
 *
 * <p>두 경우 모두 같은 durable job으로 수렴해야 하며, 반복 실행해도 재고/정산 side effect가
 * 중복되지 않아야 한다.
 */
@Testcontainers
@SpringBootTest
class PaymentWorkflowCrashWindowIntegrationTest extends PayPalIntegrationTestSupport {
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
    }

    @Autowired
    private PaymentService payments;
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
    @Autowired
    private JdbcTemplate jdbc;

    /** 실제 서비스 호출 경로를 유지하면서 provider dispatch 지점에서만 실패를 주입한다. */
    @MockitoSpyBean
    private PaymentService paymentsSpy;

    private PaymentRecoveryWorker worker;

    @BeforeEach
    void isolateRecoveryState() {
        jdbc.update("DELETE FROM payment_recovery_operator_action");
        jdbc.update("DELETE FROM payment_recovery_job");
        doCallRealMethod().when(paymentsSpy).submitCheckout(any(), any());
        doCallRealMethod().when(paymentsSpy).reconcileCheckout(any(), any());
        worker = new PaymentRecoveryWorker(
                recovery, payments, orders, checkouts, refunds, new SimpleMeterRegistry(), 1,
                Duration.ofSeconds(60), 8, Duration.ofHours(24), Duration.ofSeconds(1),
                Duration.ofMinutes(15));
    }

    @Test
    void intentCommittedBeforeProviderDispatchIsRetriedByTheDurableJob() {
        CheckoutResponse reserved = reservedCheckout();
        UUID checkoutId = reserved.checkoutId();
        UUID orderId = reserved.orders().getFirst().orderId();

        doThrow(new IllegalStateException("simulated crash before provider dispatch"))
                .when(paymentsSpy).submitCheckout(eq(checkoutId), any());
        assertThatThrownBy(() -> checkouts.submit(checkoutId))
                .isInstanceOf(IllegalStateException.class);

        // crash-window 1: 업무 상태와 attempt, recovery job은 commit됐고 provider 호출은 없었다.
        assertThat(jdbc.queryForObject(
                "SELECT state FROM buyer_checkout WHERE checkout_id = ?", String.class, checkoutId))
                .isEqualTo(BuyerCheckoutState.PAYMENT_PENDING.name());
        UUID attemptId = jdbc.queryForObject(
                "SELECT payment_attempt_id FROM purchase_order WHERE order_id = ?",
                UUID.class, orderId);
        assertThat(attemptId).isNotNull();
        assertThat(jdbc.queryForObject(
                "SELECT state FROM payment_attempt WHERE attempt_id = ?", String.class, attemptId))
                .isEqualTo(PaymentState.NOT_STARTED.name());
        assertThat(job("payment_attempt_id", attemptId))
                .containsEntry("work_stage", PaymentRecoveryWorkStage.PROVIDER.name())
                .containsEntry("status", PaymentRecoveryJobStatus.PENDING.name());
        assertThat(captureRequestCount()).isZero();

        doCallRealMethod().when(paymentsSpy).submitCheckout(any(), any());
        pollUntilSettled();

        PaymentResponse dispatched = payments.findCheckout(checkoutId);
        assertThat(dispatched.state()).isEqualTo(PaymentState.AUTHORIZATION_PENDING);
        assertThat(dispatched.providerOrderId()).isNotBlank();
        assertThat(dispatched.approvalUrl()).isNotBlank();
        // 승인 대기는 자동 재시도 대상이 아니므로 WAITING_FOR_BUYER로 남는다.
        assertThat(job("payment_attempt_id", attemptId))
                .containsEntry("work_stage", PaymentRecoveryWorkStage.PROVIDER.name())
                .containsEntry("status", PaymentRecoveryJobStatus.WAITING_FOR_BUYER.name());
    }

    @Test
    void persistedCaptureWithoutOwnerApplicationIsAppliedOnceByTheRecoveryWorker() {
        CheckoutResponse reserved = reservedCheckout();
        UUID checkoutId = reserved.checkoutId();
        UUID orderId = reserved.orders().getFirst().orderId();
        String skuId = reserved.orders().getFirst().items().getFirst().skuId();
        UUID sellerId = reserved.orders().getFirst().items().getFirst().sellerId();

        CheckoutResponse pending = checkouts.submit(checkoutId);
        String providerOrderId = payments.findCheckout(checkoutId).providerOrderId();
        assertThat(pending.paymentState()).isEqualTo(PaymentState.AUTHORIZATION_PENDING);
        // 승인·capture까지 provider 단계만 진행하고 owner 적용은 호출하지 않는다.
        PaymentResponse authorized = payments.completeCheckoutApproval(checkoutId, providerOrderId);
        assertThat(authorized.state()).isEqualTo(PaymentState.AUTHORIZED);

        // crash-window 2: provider capture는 저장됐지만 owner 적용은 실행되지 않은 상태를 만든다.
        PaymentResponse captured = payments.reconcileCheckout(checkoutId, "crash-before-owner");
        assertThat(captured.state()).isEqualTo(PaymentState.CAPTURED);
        UUID attemptId = captured.attemptId();
        assertThat(job("payment_attempt_id", attemptId))
                .containsEntry("work_stage", PaymentRecoveryWorkStage.APPLY.name())
                .containsEntry("status", PaymentRecoveryJobStatus.PENDING.name());
        assertThat(jdbc.queryForObject(
                "SELECT state FROM buyer_checkout WHERE checkout_id = ?", String.class, checkoutId))
                .isEqualTo(BuyerCheckoutState.PAYMENT_PENDING.name());
        assertThat(jdbc.queryForObject(
                "SELECT state FROM purchase_order WHERE order_id = ?", String.class, orderId))
                .isEqualTo(OrderState.PAYMENT_PENDING.name());
        assertThat(inventory.find(sellerId, skuId)).satisfies(pool -> {
            assertThat(pool.reserved()).isEqualTo(1);
            assertThat(pool.claimed()).isZero();
        });
        assertThat(settlementJournalCount(checkoutId)).isZero();

        pollUntilSettled();

        assertThat(jdbc.queryForObject(
                "SELECT state FROM buyer_checkout WHERE checkout_id = ?", String.class, checkoutId))
                .isEqualTo(BuyerCheckoutState.PAID.name());
        assertThat(jdbc.queryForObject(
                "SELECT state FROM purchase_order WHERE order_id = ?", String.class, orderId))
                .isEqualTo(OrderState.PAID.name());
        assertThat(jdbc.queryForObject(
                "SELECT status FROM order_fulfillment WHERE order_id = ?", String.class, orderId))
                .isEqualTo("UNFULFILLED");
        InventoryResponse.Pool pool = inventory.find(sellerId, skuId);
        assertThat(pool.available()).isZero();
        assertThat(pool.reserved()).isZero();
        assertThat(pool.claimed()).isEqualTo(1);
        assertThat(settlementJournalCount(checkoutId)).isEqualTo(1);
        assertThat(job("payment_attempt_id", attemptId))
                .containsEntry("work_stage", PaymentRecoveryWorkStage.APPLY.name())
                .containsEntry("status", PaymentRecoveryJobStatus.COMPLETED.name());

        // 재전달/재시작에도 정산·재고 side effect는 한 번만 남아야 한다.
        pollUntilSettled();
        assertThat(settlementJournalCount(checkoutId)).isEqualTo(1);
        assertThat(inventory.find(sellerId, skuId).claimed()).isEqualTo(1);
        assertThat(captureRequestCount()).isEqualTo(1);
    }
    @Test
    void lostRefundResponseIsRecoveredAndAppliedExactlyOnce() {
        CheckoutResponse reserved = reservedCheckout();
        UUID checkoutId = reserved.checkoutId();
        OrderViewResponse.Order reservedOrder = reserved.orders().getFirst();
        UUID orderId = reservedOrder.orderId();
        UUID sellerId = reservedOrder.items().getFirst().sellerId();
        String skuId = reservedOrder.items().getFirst().skuId();
        UUID orderItemId = jdbc.queryForObject("""
                SELECT order_item_id FROM order_item WHERE order_id = ? AND sku_id = ?
                """, UUID.class, orderId, skuId);

        CheckoutResponse pending = checkouts.submit(checkoutId);
        CheckoutResponse paid = checkouts.completeApproval(
                checkoutId, approvalToken(pending.paymentApprovalUrl()));
        assertThat(paid.orders().getFirst().state()).isEqualTo(OrderState.PAID);
        assertThat(inventory.find(sellerId, skuId).claimed()).isEqualTo(1);

        String idempotencyKey = "crash-window-refund-" + UUID.randomUUID();
        OrderRequest.Refund request = new OrderRequest.Refund(
                sellerId, orderId, idempotencyKey, List.of(new OrderRequest.Refund.Item(orderItemId, 1)));
        disconnectNextRefundResponse();
        var unknown = refunds.refund(request);

        assertThat(unknown.paymentStatus()).isEqualTo(OrderRefundPaymentStatus.UNKNOWN);
        UUID refundOperationId = jdbc.queryForObject("""
                SELECT payment_refund_operation_id FROM order_refund_request WHERE refund_id = ?
                """, UUID.class, unknown.refundId());
        UUID providerRequestId = jdbc.queryForObject("""
                SELECT provider_request_id FROM payment_refund_operation WHERE refund_operation_id = ?
                """, UUID.class, refundOperationId);
        assertThat(job("payment_refund_operation_id", refundOperationId))
                .containsEntry("work_stage", PaymentRecoveryWorkStage.PROVIDER.name())
                .containsEntry("status", PaymentRecoveryJobStatus.PENDING.name());
        assertThat(inventory.find(sellerId, skuId)).satisfies(pool -> {
            assertThat(pool.available()).isZero();
            assertThat(pool.reserved()).isZero();
            assertThat(pool.claimed()).isEqualTo(1);
        });
        assertThat(refundJournalCount(unknown.refundId())).isZero();

        pollUntilSettled();

        assertThat(jdbc.queryForObject("""
                SELECT payment_refund_status FROM order_refund_request WHERE refund_id = ?
                """, String.class, unknown.refundId()))
                .isEqualTo(OrderRefundPaymentStatus.COMPLETED.name());
        assertThat(jdbc.queryForObject("""
                SELECT refund_status FROM order_refund_request WHERE refund_id = ?
                """, String.class, unknown.refundId())).isEqualTo("FULLY_RECORDED");
        assertThat(inventory.find(sellerId, skuId)).satisfies(pool -> {
            assertThat(pool.available()).isEqualTo(1);
            assertThat(pool.reserved()).isZero();
            assertThat(pool.claimed()).isZero();
        });
        assertThat(refundJournalCount(unknown.refundId())).isEqualTo(1);
        assertThat(refundRequestIds()).hasSizeGreaterThanOrEqualTo(2)
                .containsOnly(providerRequestId.toString());
        assertThat(job("payment_refund_operation_id", refundOperationId))
                .containsEntry("work_stage", PaymentRecoveryWorkStage.APPLY.name())
                .containsEntry("status", PaymentRecoveryJobStatus.COMPLETED.name());
    }


    private CheckoutResponse reservedCheckout() {
        UUID sellerId = sellers.createSeller().sellerId();
        var product = products.createProduct(sellerId, new ProductRequest.CreateProduct("Crash window product", null));
        String skuId = "crash-window-" + UUID.randomUUID();
        products.registerSku(sellerId, product.productId(), new ProductRequest.RegisterSellerSku(skuId, 17_200, 1));
        String buyerRef = "crash-window-buyer-" + UUID.randomUUID();
        carts.setItemQuantity(sellerId, buyerRef, skuId, 1);
        return cartCheckouts.checkout(buyerRef, "crash-window-checkout-" + UUID.randomUUID());
    }

    private Map<String, Object> job(String operationColumn, UUID operationId) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT work_stage, status FROM payment_recovery_job WHERE %s = ?
                """.formatted(operationColumn), operationId);
        assertThat(rows).as("recovery job for %s", operationColumn).hasSize(1);
        return rows.getFirst();
    }

    private int settlementJournalCount(UUID checkoutId) {
        return jdbc.queryForObject("""
                SELECT COUNT(*) FROM settlement_journal WHERE checkout_id = ?
                """, Integer.class, checkoutId);
    }
    private int refundJournalCount(UUID refundId) {
        return jdbc.queryForObject("""
                SELECT COUNT(*) FROM settlement_journal WHERE source_refund_id = ?
                """, Integer.class, refundId);
    }


    private void pollUntilSettled() {
        for (int attempt = 0; attempt < 6; attempt++) {
            worker.poll();
        }
    }
}
