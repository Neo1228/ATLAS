package com.atlas.payment.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;

import com.atlas.catalog.dto.request.ProductRequest;
import com.atlas.catalog.service.ProductService;
import com.atlas.catalog.service.SellerService;
import com.atlas.order.dto.request.OrderRequest;
import com.atlas.order.dto.response.CheckoutResponse;
import com.atlas.order.model.OrderState;
import com.atlas.order.service.CartCheckoutService;
import com.atlas.order.service.CartService;
import com.atlas.order.service.CheckoutPaymentService;
import com.atlas.order.service.OrderRefundService;
import com.atlas.payment.PayPalIntegrationTestSupport;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * 같은 멱등 키로 동시에 들어온 환불 두 건이 교착(SQLSTATE 40P01) 없이 끝나는지 본다.
 *
 * <p>EXP-112 3b에서 찾은 교착: 결제 환불 작업 행을 한쪽은 UPDATE(FOR NO KEY UPDATE) 뒤 FOR UPDATE 로,
 * 다른 쪽은 FK 갱신의 FOR KEY SHARE 뒤 FOR UPDATE 로 올리다 서로를 기다렸다. 프로세스 안
 * {@link OwnerLockRegistry} 는 여러 복제본이 도는 운영 환경에서 서로를 막지 못하므로 우회한다.
 */
@Testcontainers
@SpringBootTest
@DirtiesContext
class RefundOperationRowLockIntegrationTest extends PayPalIntegrationTestSupport {
    private static final int TRIALS = 250;

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
        registry.add("atlas.payment.paypal.webhook-retry.worker.enabled", () -> "false");
        // 환불할 단위를 넉넉히 사려고 Buyer 예약 수량 상한(ADR-0113, 기본 50)을 넘는 수량을 담는다.
        registry.add("atlas.checkout-buyer-limit.reserved-units", () -> String.valueOf(Integer.MAX_VALUE));
    }

    @Autowired private SellerService sellers;
    @Autowired private ProductService products;
    @Autowired private CartService carts;
    @Autowired private CartCheckoutService checkouts;
    @Autowired private CheckoutPaymentService checkoutPayments;
    @Autowired private OrderRefundService refunds;
    @Autowired private JdbcTemplate jdbc;
    @MockitoSpyBean private OwnerLockRegistry ownerLocks;

    @Test
    void concurrentRefundsWithTheSameKeyDoNotDeadlock() throws Exception {
        doAnswer(invocation -> {
            Supplier<?> action = invocation.getArgument(1);
            return action.get();
        }).when(ownerLocks).withLock(any(), any());
        UUID sellerId = sellers.createSeller().sellerId();
        var product = products.createProduct(sellerId, new ProductRequest.CreateProduct("refund lock product", null));
        String skuId = "lock-sku-" + UUID.randomUUID();
        products.registerSku(sellerId, product.productId(), new ProductRequest.RegisterSellerSku(skuId, 1000L, 20_000_000));
        String buyerRef = "lock-buyer-" + UUID.randomUUID();
        carts.setItemQuantity(sellerId, buyerRef, skuId, TRIALS + 50);
        CheckoutResponse checkout = checkouts.checkout(buyerRef, "lock-checkout-" + UUID.randomUUID());
        UUID orderId = checkout.orders().get(0).orderId();
        UUID itemId = jdbc.queryForObject("SELECT order_item_id FROM order_item WHERE order_id = ?", UUID.class, orderId);
        CheckoutResponse pending = checkoutPayments.submit(checkout.checkoutId());
        CheckoutResponse paid = checkoutPayments.completeApproval(checkout.checkoutId(),
                approvalToken(pending.paymentApprovalUrl()));
        assertThat(paid.orders().get(0).state()).isEqualTo(OrderState.PAID);

        ExecutorService pool = Executors.newFixedThreadPool(2);
        List<String> failures = new ArrayList<>();
        try {
            for (int trial = 0; trial < TRIALS; trial++) {
                String key = "lock-refund-" + trial;
                CyclicBarrier barrier = new CyclicBarrier(2);
                List<Future<Void>> calls = new ArrayList<>();
                for (int caller = 0; caller < 2; caller++) {
                    calls.add(pool.submit(() -> {
                        barrier.await(30, TimeUnit.SECONDS);
                        refunds.refund(new OrderRequest.Refund(sellerId, orderId, key,
                                List.of(new OrderRequest.Refund.Item(itemId, 1))));
                        return null;
                    }));
                }
                for (Future<Void> call : calls) {
                    try {
                        call.get(120, TimeUnit.SECONDS);
                    } catch (Exception exception) {
                        failures.add("trial " + trial + ": " + sqlState(exception));
                    }
                }
            }
        } finally {
            pool.shutdownNow();
        }
        assertThat(failures).as("같은 키 동시 환불 %d회 중 실패", TRIALS).isEmpty();
    }

    private static String sqlState(Throwable throwable) {
        for (Throwable cause = throwable; cause != null; cause = cause.getCause() == cause ? null : cause.getCause()) {
            if (cause instanceof SQLException sql && sql.getSQLState() != null) return "SQLSTATE " + sql.getSQLState();
        }
        return throwable.getClass().getSimpleName();
    }
}
