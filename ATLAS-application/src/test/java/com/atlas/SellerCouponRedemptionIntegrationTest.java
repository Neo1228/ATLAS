package com.atlas;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.atlas.cart.service.CartService;
import com.atlas.catalog.dto.request.CreateProductRequest;
import com.atlas.catalog.dto.request.RegisterSellerSkuRequest;
import com.atlas.catalog.service.ProductService;
import com.atlas.catalog.service.SellerCouponService;
import com.atlas.catalog.service.SellerService;
import com.atlas.catalog.exception.CatalogException;
import com.atlas.inventory.dto.response.InventoryPoolResponse;
import com.atlas.inventory.service.InventoryService;
import com.atlas.order.dto.request.OrderRefundRequest;
import com.atlas.order.dto.request.SellerCouponRequest;
import com.atlas.order.dto.response.CheckoutResponse;
import com.atlas.order.dto.response.OrderRefundResponse;
import com.atlas.order.persistence.state.BuyerCheckoutState;
import com.atlas.order.service.CartCheckoutService;
import com.atlas.order.service.OrderRefundService;
import com.atlas.order.service.impl.CheckoutPaymentServiceImpl;
import com.atlas.payment.persistence.state.FakePspResult;
import com.atlas.payment.persistence.state.PaymentPhase;
import com.atlas.payment.persistence.state.PaymentState;
import com.atlas.payment.provider.fake.FakePsp;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@Testcontainers
@SpringBootTest
@AutoConfigureMockMvc
class SellerCouponRedemptionIntegrationTest {
    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18.6")
            .withDatabaseName("atlas")
            .withUsername("atlas")
            .withPassword("atlas");

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry properties) {
        properties.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        properties.add("spring.datasource.username", POSTGRES::getUsername);
        properties.add("spring.datasource.password", POSTGRES::getPassword);
        properties.add("spring.datasource.hikari.maximum-pool-size", () -> "32");
        properties.add("atlas.admission.expiry-poll-interval", () -> "PT24H");
        properties.add("atlas.orders.expiry-poll-interval", () -> "PT24H");
    }

    @Autowired private SellerService sellers;
    @Autowired private ProductService products;
    @Autowired private SellerCouponService coupons;
    @Autowired private CartService carts;
    @Autowired private MockMvc mockMvc;
    @Autowired private CartCheckoutService checkouts;
    @Autowired private CheckoutPaymentServiceImpl checkoutPayments;
    @Autowired private OrderRefundService refunds;
    @Autowired private InventoryService inventory;
    @Autowired private FakePsp fakePsp;
    @Autowired private JdbcTemplate jdbc;

    @Test
    void concurrentCheckoutsCannotExceedCouponCapAndRefundDoesNotRestoreUses() throws Exception {
        int cap = 10;
        int attempts = 32;
        SkuFixture sku = createSku(sellers.createSeller().sellerId(), 1_000, attempts);
        String code = createCoupon(sku.sellerId(), 100, cap);
        mockMvc.perform(get("/api/sellers/{sellerId}/coupons/{code}", sku.sellerId(), code))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.maxRedemptions").value(cap))
                .andExpect(jsonPath("$.reservedCount").value(0))
                .andExpect(jsonPath("$.remainingCount").value(cap));
        List<String> buyers = new ArrayList<>(attempts);
        for (int index = 0; index < attempts; index++) {
            String buyer = "cap-race-buyer-" + UUID.randomUUID();
            buyers.add(buyer);
            carts.setItemQuantity(sku.sellerId(), buyer, sku.skuId(), 1);
        }

        ExecutorService workers = Executors.newFixedThreadPool(attempts);
        CountDownLatch ready = new CountDownLatch(attempts);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<CheckoutResponse>> attemptsInFlight = new ArrayList<>(attempts);
        try {
            for (int index = 0; index < attempts; index++) {
                int attempt = index;
                attemptsInFlight.add(workers.submit(() -> {
                    ready.countDown();
                    if (!start.await(30, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("Coupon race did not start");
                    }
                    try {
                        return checkouts.checkout(buyers.get(attempt), "cap-race-key-" + attempt,
                                List.of(new SellerCouponRequest(sku.sellerId(), code)));
                    } catch (CatalogException conflict) {
                        if (conflict.code() == CatalogException.Code.CONFLICT
                                && conflict.getMessage().contains("usage limit")) return null;
                        throw conflict;
                    }
                }));
            }
            assertThat(ready.await(30, TimeUnit.SECONDS)).isTrue();
            start.countDown();

            List<CheckoutResponse> reserved = new ArrayList<>(cap);
            int rejected = 0;
            for (Future<CheckoutResponse> attempt : attemptsInFlight) {
                try {
                    CheckoutResponse checkout = attempt.get(60, TimeUnit.SECONDS);
                    if (checkout == null) rejected++;
                    else reserved.add(checkout);
                } catch (ExecutionException failure) {
                    throw new AssertionError("Unexpected coupon checkout failure", failure.getCause());
                }
            }
            assertThat(reserved).hasSize(cap);
            assertThat(rejected).isEqualTo(attempts - cap);
            assertThat(counts(sku.sellerId(), code)).isEqualTo(new CouponCounts(cap, 0, cap, 0, 0));
            var reservedUsage = coupons.find(sku.sellerId(), code);
            assertThat(reservedUsage.maxRedemptions()).isEqualTo(10L);
            assertThat(reservedUsage.reservedCount()).isEqualTo(10L);
            assertThat(reservedUsage.remainingCount()).isZero();
            mockMvc.perform(get("/api/sellers/{sellerId}/coupons/{code}", sku.sellerId(), code))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.reservedCount").value(cap))
                    .andExpect(jsonPath("$.redeemedCount").value(0))
                    .andExpect(jsonPath("$.remainingCount").value(0));

            List<CheckoutResponse> paid = reserved.stream()
                    .map(checkout -> checkoutPayments.submit(checkout.checkoutId())).toList();
            assertThat(paid).allSatisfy(checkout -> assertThat(checkout.paymentState()).isEqualTo(PaymentState.CAPTURED));
            assertThat(counts(sku.sellerId(), code)).isEqualTo(new CouponCounts(0, cap, 0, cap, 0));
            var redeemedUsage = coupons.find(sku.sellerId(), code);
            assertThat(redeemedUsage.reservedCount()).isZero();
            assertThat(redeemedUsage.redeemedCount()).isEqualTo(10L);
            assertThat(redeemedUsage.remainingCount()).isZero();

            var order = paid.getFirst().orders().getFirst();
            UUID orderItemId = orderItemId(order.orderId(), sku.skuId());
            OrderRefundResponse refund = refunds.refund(new OrderRefundRequest(sku.sellerId(), order.orderId(),
                    "cap-refund-" + UUID.randomUUID(), List.of(new OrderRefundRequest.Item(orderItemId, 1))));
            assertThat(refund.recordedAmountWon()).isEqualTo(900);
            assertThat(counts(sku.sellerId(), code)).isEqualTo(new CouponCounts(0, cap, 0, cap, 0));

            String nextBuyer = "cap-exhausted-buyer-" + UUID.randomUUID();
            carts.setItemQuantity(sku.sellerId(), nextBuyer, sku.skuId(), 1);
            assertThatThrownBy(() -> checkouts.checkout(nextBuyer, "cap-exhausted-key-" + UUID.randomUUID(),
                    List.of(new SellerCouponRequest(sku.sellerId(), code))))
                    .isInstanceOf(CatalogException.class)
                    .satisfies(error -> assertThat(((CatalogException) error).code())
                            .isEqualTo(CatalogException.Code.CONFLICT));
            assertThat(counts(sku.sellerId(), code)).isEqualTo(new CouponCounts(0, cap, 0, cap, 0));
        } finally {
            start.countDown();
            workers.shutdownNow();
        }
    }

    @Test
    void redemptionCounterAndCheckoutRollbackTogetherWhenLedgerInsertFails() {
        SkuFixture sku = createSku(sellers.createSeller().sellerId(), 1_000, 1);
        String code = createCoupon(sku.sellerId(), 100, 1);
        String buyer = "cap-rollback-buyer-" + UUID.randomUUID();
        carts.setItemQuantity(sku.sellerId(), buyer, sku.skuId(), 1);
        jdbc.execute("""
                CREATE OR REPLACE FUNCTION fail_coupon_redemption_insert() RETURNS TRIGGER AS $$
                BEGIN
                    RAISE EXCEPTION 'simulated coupon redemption ledger failure';
                END;
                $$ LANGUAGE plpgsql
                """);
        jdbc.execute("""
                CREATE TRIGGER fail_coupon_redemption_insert
                BEFORE INSERT ON seller_coupon_redemption
                FOR EACH ROW EXECUTE FUNCTION fail_coupon_redemption_insert()
                """);
        try {
            assertThatThrownBy(() -> checkouts.checkout(buyer, "cap-rollback-key-" + UUID.randomUUID(),
                    List.of(new SellerCouponRequest(sku.sellerId(), code))))
                    .isInstanceOf(RuntimeException.class);
        } finally {
            jdbc.execute("DROP TRIGGER IF EXISTS fail_coupon_redemption_insert ON seller_coupon_redemption");
            jdbc.execute("DROP FUNCTION IF EXISTS fail_coupon_redemption_insert()");
        }

        assertThat(counts(sku.sellerId(), code)).isEqualTo(new CouponCounts(0, 0, 0, 0, 0));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM buyer_checkout WHERE buyer_ref = ?",
                Integer.class, buyer)).isZero();
        assertThat(carts.find(buyer).items()).hasSize(1);
        InventoryPoolResponse stock = inventory.find(sku.sellerId(), sku.skuId());
        assertThat(stock.available()).isEqualTo(1);
        assertThat(stock.reserved()).isZero();
    }

    @Test
    void unknownPaymentHoldsCapacityUntilCaptureIsReconciledEvenAfterCouponDeactivation() {
        SkuFixture sku = createSku(sellers.createSeller().sellerId(), 1_000, 2);
        String code = createCoupon(sku.sellerId(), 100, 1);
        String buyer = "cap-unknown-buyer-" + UUID.randomUUID();
        CheckoutResponse reserved = checkout(buyer, sku, code);
        fakePsp.enqueue(PaymentPhase.AUTHORIZATION, FakePspResult.UNKNOWN, FakePspResult.AUTHORIZED);
        fakePsp.enqueue(PaymentPhase.CAPTURE, FakePspResult.PENDING, FakePspResult.UNKNOWN, FakePspResult.CAPTURED);

        CheckoutResponse unknown = checkoutPayments.submit(reserved.checkoutId());
        assertThat(unknown.paymentState()).isEqualTo(PaymentState.UNKNOWN);
        assertThat(counts(sku.sellerId(), code)).isEqualTo(new CouponCounts(1, 0, 1, 0, 0));

        coupons.deactivate(sku.sellerId(), code);
        CheckoutResponse capturePending = checkoutPayments.reconcile(reserved.checkoutId(), "cap-reconcile-1");
        assertThat(capturePending.paymentState()).isEqualTo(PaymentState.CAPTURE_PENDING);
        assertThat(counts(sku.sellerId(), code)).isEqualTo(new CouponCounts(1, 0, 1, 0, 0));

        CheckoutResponse captureUnknown = checkoutPayments.reconcile(reserved.checkoutId(), "cap-reconcile-2");
        assertThat(captureUnknown.paymentState()).isEqualTo(PaymentState.UNKNOWN);
        assertThat(counts(sku.sellerId(), code)).isEqualTo(new CouponCounts(1, 0, 1, 0, 0));

        CheckoutResponse captured = checkoutPayments.reconcile(reserved.checkoutId(), "cap-reconcile-3");
        assertThat(captured.paymentState()).isEqualTo(PaymentState.CAPTURED);
        assertThat(counts(sku.sellerId(), code)).isEqualTo(new CouponCounts(0, 1, 0, 1, 0));
        checkoutPayments.reconcile(reserved.checkoutId(), "cap-reconcile-4");
        assertThat(counts(sku.sellerId(), code)).isEqualTo(new CouponCounts(0, 1, 0, 1, 0));
    }

    @Test
    void voidPendingKeepsCapacityUntilVoidIsConfirmed() {
        SkuFixture sku = createSku(sellers.createSeller().sellerId(), 1_000, 2);
        String code = createCoupon(sku.sellerId(), 100, 1);
        CheckoutResponse reserved = checkout("cap-void-buyer-" + UUID.randomUUID(), sku, code);
        fakePsp.enqueue(PaymentPhase.AUTHORIZATION, FakePspResult.AUTHORIZED);
        fakePsp.enqueue(PaymentPhase.CAPTURE, FakePspResult.DECLINED);
        fakePsp.enqueue(PaymentPhase.VOID, FakePspResult.PENDING, FakePspResult.VOIDED);

        CheckoutResponse voidPending = checkoutPayments.submit(reserved.checkoutId());
        assertThat(voidPending.paymentState()).isEqualTo(PaymentState.VOID_PENDING);
        assertThat(counts(sku.sellerId(), code)).isEqualTo(new CouponCounts(1, 0, 1, 0, 0));

        CheckoutResponse voided = checkoutPayments.reconcile(reserved.checkoutId(), "cap-void-reconcile");
        assertThat(voided.paymentState()).isEqualTo(PaymentState.VOIDED);
        assertThat(voided.state()).isEqualTo(BuyerCheckoutState.FAILED);
        assertThat(counts(sku.sellerId(), code)).isEqualTo(new CouponCounts(0, 0, 0, 0, 1));
    }

    @Test
    void cancellationAndPrePaymentExpiryReleaseCapacityExactlyOnce() {
        SkuFixture sku = createSku(sellers.createSeller().sellerId(), 1_000, 3);
        String code = createCoupon(sku.sellerId(), 100, 1);
        CheckoutResponse reserved = checkout("cap-cancel-buyer-" + UUID.randomUUID(), sku, code);
        CheckoutResponse cancelled = checkoutPayments.cancel(reserved.checkoutId(), "cap-cancel");
        assertThat(cancelled.state()).isEqualTo(BuyerCheckoutState.CANCELLED);
        assertThat(counts(sku.sellerId(), code)).isEqualTo(new CouponCounts(0, 0, 0, 0, 1));

        CheckoutResponse expires = checkout("cap-expire-buyer-" + UUID.randomUUID(), sku, code);
        jdbc.update("UPDATE purchase_order SET checkout_expires_at = ? WHERE checkout_id = ?",
                Timestamp.from(Instant.now().minusSeconds(1)), expires.checkoutId());
        assertThat(checkoutPayments.expireDueCheckouts()).isEqualTo(1);
        assertThat(counts(sku.sellerId(), code)).isEqualTo(new CouponCounts(0, 0, 0, 0, 2));

        CheckoutResponse next = checkout("cap-after-release-buyer-" + UUID.randomUUID(), sku, code);
        assertThat(next.state()).isEqualTo(BuyerCheckoutState.RESERVED);
        assertThat(counts(sku.sellerId(), code)).isEqualTo(new CouponCounts(1, 0, 1, 0, 2));
    }

    @Test
    void exhaustedCouponInLaterSellerOrderRollsBackEarlierSellerReservation() {
        UUID sellerA = sellers.createSeller().sellerId();
        UUID sellerB = sellers.createSeller().sellerId();
        UUID firstSeller = List.of(sellerA, sellerB).stream().min(Comparator.naturalOrder()).orElseThrow();
        UUID secondSeller = sellerA.equals(firstSeller) ? sellerB : sellerA;
        SkuFixture firstSku = createSku(firstSeller, 1_000, 2);
        SkuFixture secondSku = createSku(secondSeller, 1_000, 2);
        String firstCode = createCoupon(firstSeller, 100, 1);
        String secondCode = createCoupon(secondSeller, 100, 1);

        CheckoutResponse consumed = checkout("cap-preconsume-buyer-" + UUID.randomUUID(), secondSku, secondCode);
        assertThat(checkoutPayments.submit(consumed.checkoutId()).paymentState()).isEqualTo(PaymentState.CAPTURED);
        assertThat(counts(secondSeller, secondCode)).isEqualTo(new CouponCounts(0, 1, 0, 1, 0));

        String buyer = "cap-multiseller-buyer-" + UUID.randomUUID();
        carts.setItemQuantity(firstSeller, buyer, firstSku.skuId(), 1);
        carts.setItemQuantity(secondSeller, buyer, secondSku.skuId(), 1);
        assertThatThrownBy(() -> checkouts.checkout(buyer, "cap-multiseller-key",
                List.of(new SellerCouponRequest(firstSeller, firstCode),
                        new SellerCouponRequest(secondSeller, secondCode))))
                .isInstanceOf(CatalogException.class)
                .satisfies(error -> assertThat(((CatalogException) error).code())
                        .isEqualTo(CatalogException.Code.CONFLICT));

        assertThat(counts(firstSeller, firstCode)).isEqualTo(new CouponCounts(0, 0, 0, 0, 0));
        assertThat(counts(secondSeller, secondCode)).isEqualTo(new CouponCounts(0, 1, 0, 1, 0));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM buyer_checkout WHERE buyer_ref = ?",
                Integer.class, buyer)).isZero();
        assertThat(carts.find(buyer).items()).hasSize(2);
        assertThat(inventory.find(firstSeller, firstSku.skuId()).reserved()).isZero();
    }

    @Test
    void couponRedemptionTerminalTransitionsAreIdempotent() {
        UUID sellerId = sellers.createSeller().sellerId();
        SkuFixture sku = createSku(sellerId, 1_000, 2);
        String code = createCoupon(sellerId, 100, 2);
        UUID couponId = coupons.find(sellerId, code).couponId();
        Instant recordedAt = Instant.now();
        UUID releasedOrderId = checkout("terminal-release-" + UUID.randomUUID(), sku, code)
                .orders().getFirst().orderId();
        UUID redeemedOrderId = checkout("terminal-redeem-" + UUID.randomUUID(), sku, code)
                .orders().getFirst().orderId();

        coupons.reserveRedemption(sellerId, couponId, releasedOrderId, recordedAt);
        coupons.reserveRedemption(sellerId, couponId, releasedOrderId, recordedAt);
        assertThat(coupons.find(sellerId, code).reservedCount()).isEqualTo(1L);
        coupons.releaseRedemption(sellerId, couponId, releasedOrderId, recordedAt);
        coupons.releaseRedemption(sellerId, couponId, releasedOrderId, recordedAt);
        assertThatThrownBy(() -> coupons.reserveRedemption(sellerId, couponId, releasedOrderId, recordedAt))
                .isInstanceOf(CatalogException.class)
                .satisfies(error -> assertThat(((CatalogException) error).code())
                        .isEqualTo(CatalogException.Code.CONFLICT));

        coupons.reserveRedemption(sellerId, couponId, redeemedOrderId, recordedAt);
        coupons.redeemRedemption(sellerId, couponId, redeemedOrderId, recordedAt);
        coupons.redeemRedemption(sellerId, couponId, redeemedOrderId, recordedAt);
        assertThatThrownBy(() -> coupons.releaseRedemption(sellerId, couponId, redeemedOrderId, recordedAt))
                .isInstanceOf(CatalogException.class)
                .satisfies(error -> assertThat(((CatalogException) error).code())
                        .isEqualTo(CatalogException.Code.CONFLICT));

        var coupon = coupons.find(sellerId, code);
        assertThat(coupon.reservedCount()).isZero();
        assertThat(coupon.redeemedCount()).isEqualTo(1L);
        assertThat(coupon.remainingCount()).isEqualTo(1L);
    }

    private CheckoutResponse checkout(String buyer, SkuFixture sku, String code) {
        carts.setItemQuantity(sku.sellerId(), buyer, sku.skuId(), 1);
        return checkouts.checkout(buyer, "coupon-cap-key-" + UUID.randomUUID(),
                List.of(new SellerCouponRequest(sku.sellerId(), code)));
    }

    private SkuFixture createSku(UUID sellerId, long priceWon, int initialStock) {
        String skuId = "redemption-" + UUID.randomUUID();
        var product = products.createProduct(sellerId,
                new CreateProductRequest("Coupon redemption " + skuId, "local integration fixture"));
        products.registerSku(sellerId, product.productId(),
                new RegisterSellerSkuRequest(skuId, priceWon, initialStock));
        return new SkuFixture(sellerId, skuId);
    }

    private String createCoupon(UUID sellerId, long discountWon, long maxRedemptions) {
        String code = ("CAP" + UUID.randomUUID().toString().replace("-", "").substring(0, 29))
                .toUpperCase(Locale.ROOT);
        Instant now = Instant.now();
        coupons.create(sellerId, code, discountWon, now.minus(Duration.ofMinutes(1)),
                now.plus(Duration.ofHours(1)), maxRedemptions);
        return code;
    }

    private CouponCounts counts(UUID sellerId, String code) {
        return jdbc.queryForObject("""
                SELECT coupon.reserved_count,
                       coupon.redeemed_count,
                       (SELECT count(*) FROM seller_coupon_redemption redemption
                         WHERE redemption.seller_id = coupon.seller_id
                           AND redemption.coupon_id = coupon.coupon_id
                           AND redemption.state = 'RESERVED'),
                       (SELECT count(*) FROM seller_coupon_redemption redemption
                         WHERE redemption.seller_id = coupon.seller_id
                           AND redemption.coupon_id = coupon.coupon_id
                           AND redemption.state = 'REDEEMED'),
                       (SELECT count(*) FROM seller_coupon_redemption redemption
                         WHERE redemption.seller_id = coupon.seller_id
                           AND redemption.coupon_id = coupon.coupon_id
                           AND redemption.state = 'RELEASED')
                  FROM seller_coupon coupon
                 WHERE coupon.seller_id = ? AND coupon.code = ?
                """, (resultSet, rowNum) -> new CouponCounts(resultSet.getLong(1), resultSet.getLong(2),
                resultSet.getLong(3), resultSet.getLong(4), resultSet.getLong(5)), sellerId, code);
    }

    private UUID orderItemId(UUID orderId, String skuId) {
        return jdbc.queryForObject("SELECT order_item_id FROM order_item WHERE order_id = ? AND sku_id = ?",
                UUID.class, orderId, skuId);
    }

    private record SkuFixture(UUID sellerId, String skuId) { }
    private record CouponCounts(long reserved, long redeemed, long reservedRows, long redeemedRows,
                                long releasedRows) { }
}
