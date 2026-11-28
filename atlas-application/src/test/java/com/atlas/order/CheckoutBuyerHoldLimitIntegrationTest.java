package com.atlas.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.atlas.catalog.dto.request.ProductRequest;
import com.atlas.catalog.service.ProductService;
import com.atlas.catalog.service.SellerService;
import com.atlas.inventory.service.InventoryService;
import com.atlas.order.dto.response.CheckoutResponse;
import com.atlas.order.exception.OrderException;
import com.atlas.order.model.BuyerCheckoutState;
import com.atlas.order.service.CartCheckoutService;
import com.atlas.order.service.CartService;
import com.atlas.order.service.CheckoutPaymentService;
import com.atlas.payment.PayPalIntegrationTestSupport;
import java.time.Duration;
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

/**
 * Buyer 한 명이 결제하지 않은 checkout으로 재고를 독점하지 못하는지 검증한다(ADR-0113).
 *
 * <p>결함: checkout의 유일성은 {@code (buyer_ref, idempotency_key)}뿐이고 수량 상한도 없었다.
 * 재고 5개인 SKU를 Buyer 한 명이 5개 담아 checkout하면 checkout window(10분) 동안 다른 Buyer는
 * {@code INVENTORY_UNAVAILABLE}을 받고, 만료 뒤 같은 일을 되풀이할 수 있었다. 수량 합 상한(기본 50)만으로는
 * 재고 50개 이하 SKU를 여전히 통째로 잡을 수 있어, 만료된 hold의 SKU에 재checkout 쿨다운을 더했다.
 * 만료 대신 window 끝 직전에 취소하면 쿨다운을 피하므로, 취소한 hold의 보유 시간 합에도 같은 쿨다운을 건다.
 *
 * <p>결제까지 이어지는 정상 흐름을 확인하려고 PayPal stub을 쓴다.
 */
@Testcontainers
@SpringBootTest
class CheckoutBuyerHoldLimitIntegrationTest extends PayPalIntegrationTestSupport {
    private static final int STOCK = 5;
    /** 운영 기본 예약 수량 상한(CartCheckoutServiceImpl). 이 클래스는 운영 기본값으로 돈다. */
    private static final int RESERVED_UNIT_LIMIT = 50;

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18.6");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("atlas.admission.expiry-poll-interval", () -> "PT24H");
        registry.add("atlas.orders.expiry-poll-interval", () -> "PT24H");
    }

    @Autowired private SellerService sellers;
    @Autowired private ProductService products;
    @Autowired private CartService carts;
    @Autowired private CartCheckoutService checkouts;
    @Autowired private CheckoutPaymentService checkoutPayments;
    @Autowired private InventoryService inventory;
    @Autowired private JdbcTemplate jdbc;

    @Test
    void oneBuyerCannotHoldTheWholeStockAndOtherBuyersCanStillCheckout() {
        Sku sku = sku("hoard", RESERVED_UNIT_LIMIT + 1);
        String hoarder = "hoarder-" + UUID.randomUUID();
        carts.setItemQuantity(sku.sellerId(), hoarder, sku.skuId(), RESERVED_UNIT_LIMIT + 1);

        assertThatThrownBy(() -> checkouts.checkout(hoarder, "hoard-" + UUID.randomUUID()))
                .isInstanceOfSatisfying(OrderException.class,
                        rejected -> assertThat(rejected.code()).isEqualTo(OrderException.Code.INVALID_REQUEST));
        assertThat(inventory.find(sku.sellerId(), sku.skuId()).reserved()).isZero();
        String other = "other-buyer-" + UUID.randomUUID();
        carts.setItemQuantity(sku.sellerId(), other, sku.skuId(), 1);
        assertThat(checkouts.checkout(other, "other-" + UUID.randomUUID()).state())
                .isEqualTo(BuyerCheckoutState.RESERVED);
    }

    /** 재고가 수량 상한(50)보다 적은 SKU. 쿨다운이 없으면 만료 직후 같은 Buyer가 다시 통째로 잡는다. */
    @Test
    void buyerWhoLetAWholeStockHoldExpireCannotImmediatelyHoldItAgain() {
        Sku sku = sku("hoard-loop");
        String hoarder = "hoard-loop-" + UUID.randomUUID();
        carts.setItemQuantity(sku.sellerId(), hoarder, sku.skuId(), STOCK - 1);
        CheckoutResponse held = checkouts.checkout(hoarder, "hold-" + UUID.randomUUID());
        assertThat(held.state()).isEqualTo(BuyerCheckoutState.RESERVED);

        jdbc.update("UPDATE purchase_order SET checkout_expires_at = now() - interval '1 second' WHERE checkout_id = ?",
                held.checkoutId());
        checkoutPayments.expireDueCheckouts();
        assertThat(inventory.find(sku.sellerId(), sku.skuId()).reserved()).isZero();

        carts.setItemQuantity(sku.sellerId(), hoarder, sku.skuId(), STOCK - 1);
        assertThatThrownBy(() -> checkouts.checkout(hoarder, "again-" + UUID.randomUUID()))
                .isInstanceOfSatisfying(OrderException.class,
                        rejected -> assertThat(rejected.code()).isEqualTo(OrderException.Code.CONFLICT));
        assertThat(inventory.find(sku.sellerId(), sku.skuId()).reserved()).isZero();
        String other = "loop-other-" + UUID.randomUUID();
        carts.setItemQuantity(sku.sellerId(), other, sku.skuId(), STOCK);
        CheckoutResponse otherHold = checkouts.checkout(other, "other-" + UUID.randomUUID());
        assertThat(otherHold.state()).isEqualTo(BuyerCheckoutState.RESERVED);

        // 쿨다운(기본 30분)이 지나면 같은 Buyer도 그 SKU를 다시 살 수 있다.
        jdbc.update("UPDATE purchase_order SET checkout_expires_at = now() - interval '31 minutes' WHERE checkout_id = ?",
                held.checkoutId());
        checkoutPayments.cancel(otherHold.checkoutId(), "release-" + UUID.randomUUID());
        carts.setItemQuantity(sku.sellerId(), hoarder, sku.skuId(), 1);
        assertThat(checkouts.checkout(hoarder, "after-cooldown-" + UUID.randomUUID()).state())
                .isEqualTo(BuyerCheckoutState.RESERVED);
    }

    @Test
    void secondCheckoutWaitsUntilTheOpenOneIsCancelledOrExpiredButTheSameKeyStillReplays() {
        Sku sku = sku("open-limit");
        String buyer = "open-limit-buyer-" + UUID.randomUUID();
        carts.setItemQuantity(sku.sellerId(), buyer, sku.skuId(), 1);
        String firstKey = "first-" + UUID.randomUUID();
        CheckoutResponse first = checkouts.checkout(buyer, firstKey);
        carts.setItemQuantity(sku.sellerId(), buyer, sku.skuId(), 1);

        assertThatThrownBy(() -> checkouts.checkout(buyer, "second-" + UUID.randomUUID()))
                .isInstanceOfSatisfying(OrderException.class,
                        rejected -> assertThat(rejected.code()).isEqualTo(OrderException.Code.CONFLICT));
        assertThat(inventory.find(sku.sellerId(), sku.skuId()).reserved()).isEqualTo(1);
        assertThat(checkouts.checkout(buyer, firstKey).checkoutId()).isEqualTo(first.checkoutId());
        checkoutPayments.cancel(first.checkoutId(), "cancel-" + UUID.randomUUID());
        CheckoutResponse afterCancel = checkouts.checkout(buyer, "after-cancel-" + UUID.randomUUID());
        assertThat(afterCancel.state()).isEqualTo(BuyerCheckoutState.RESERVED);

        // window가 지난 RESERVED는 만료 sweep 전이라도 결제할 수 없으므로 새 checkout을 막지 않는다.
        // 같은 SKU는 만료 쿨다운에 걸리므로 다른 SKU로 확인한다.
        jdbc.update("UPDATE purchase_order SET checkout_expires_at = now() - interval '1 second' WHERE checkout_id = ?",
                afterCancel.checkoutId());
        Sku next = sku("open-limit-next");
        carts.setItemQuantity(next.sellerId(), buyer, next.skuId(), 1);
        assertThat(checkouts.checkout(buyer, "after-expiry-" + UUID.randomUUID()).state())
                .isEqualTo(BuyerCheckoutState.RESERVED);
    }

    /**
     * 만료 쿨다운만 있으면 window(10분) 끝 직전에 취소하고 곧바로 다시 잡아 SKU를 끝없이 독점할 수 있었다.
     * 취소한 hold의 보유 시간 합이 운영 기본 상한(5분)을 넘으면 같은 SKU는 쿨다운(30분) 동안 거절하고,
     * 그 사이 다른 Buyer가 살 수 있다. 다른 SKU는 막지 않는다.
     */
    @Test
    void buyerWhoCancelsWholeStockHoldsJustBeforeTheWindowEndsCannotLoopThem() {
        Sku sku = sku("cancel-loop");
        String hoarder = "cancel-loop-" + UUID.randomUUID();
        String firstKey = "loop-first-" + UUID.randomUUID();
        int holds = 0;
        OrderException rejected = null;
        while (rejected == null && holds < 4) {
            carts.setItemQuantity(sku.sellerId(), hoarder, sku.skuId(), STOCK);
            try {
                CheckoutResponse held = checkouts.checkout(hoarder, holds == 0 ? firstKey : "loop-" + UUID.randomUUID());
                holds++;
                heldFor(held.checkoutId(), Duration.ofMinutes(9).plusSeconds(59));
                checkoutPayments.cancel(held.checkoutId(), "loop-cancel-" + UUID.randomUUID());
            } catch (OrderException e) {
                rejected = e;
            }
        }
        assertThat(rejected).isNotNull();
        assertThat(rejected.code()).isEqualTo(OrderException.Code.CONFLICT);
        assertThat(holds).isEqualTo(1);
        assertThat(inventory.find(sku.sellerId(), sku.skuId()).reserved()).isZero();
        // 같은 Idempotency-Key 재시도는 새 hold가 아니므로 쿨다운에 걸리지 않고 기존 checkout을 돌려준다.
        assertThat(checkouts.checkout(hoarder, firstKey).state()).isEqualTo(BuyerCheckoutState.CANCELLED);

        String other = "cancel-loop-other-" + UUID.randomUUID();
        carts.setItemQuantity(sku.sellerId(), other, sku.skuId(), STOCK);
        CheckoutResponse otherHold = checkouts.checkout(other, "other-" + UUID.randomUUID());
        assertThat(otherHold.state()).isEqualTo(BuyerCheckoutState.RESERVED);

        carts.removeItem(sku.sellerId(), hoarder, sku.skuId());
        Sku next = sku("cancel-loop-next");
        carts.setItemQuantity(next.sellerId(), hoarder, next.skuId(), STOCK);
        CheckoutResponse nextHold = checkouts.checkout(hoarder, "next-sku-" + UUID.randomUUID());
        assertThat(nextHold.state()).isEqualTo(BuyerCheckoutState.RESERVED);

        // 쿨다운(기본 30분)이 지나면 같은 Buyer도 그 SKU를 다시 살 수 있다. 보유 시간은 그대로 두고 시각만 옮긴다.
        checkoutPayments.cancel(nextHold.checkoutId(), "next-release-" + UUID.randomUUID());
        checkoutPayments.cancel(otherHold.checkoutId(), "other-release-" + UUID.randomUUID());
        jdbc.update("UPDATE buyer_checkout SET created_at = created_at - interval '31 minutes', "
                + "updated_at = updated_at - interval '31 minutes' WHERE buyer_ref = ?", hoarder);
        carts.setItemQuantity(sku.sellerId(), hoarder, sku.skuId(), 1);
        assertThat(checkouts.checkout(hoarder, "after-cooldown-" + UUID.randomUUID()).state())
                .isEqualTo(BuyerCheckoutState.RESERVED);
    }

    /** 마음을 바꿔 곧바로 취소하고 수량을 바꿔 다시 사는 흐름은 운영 기본값에서 두 번까지 막히지 않고 결제된다. */
    @Test
    void buyerWhoQuicklyCancelsAndChecksOutTheSameSkuAgainCanStillPay() {
        Sku sku = sku("cancel-then-buy");
        String buyer = "cancel-then-buy-" + UUID.randomUUID();
        for (int quantity = 1; quantity <= 2; quantity++) {
            carts.setItemQuantity(sku.sellerId(), buyer, sku.skuId(), quantity);
            CheckoutResponse changedMind = checkouts.checkout(buyer, "changed-mind-" + UUID.randomUUID());
            heldFor(changedMind.checkoutId(), Duration.ofMinutes(2));
            checkoutPayments.cancel(changedMind.checkoutId(), "changed-mind-cancel-" + UUID.randomUUID());
        }

        carts.setItemQuantity(sku.sellerId(), buyer, sku.skuId(), STOCK);
        CheckoutResponse kept = checkouts.checkout(buyer, "kept-" + UUID.randomUUID());
        assertThat(kept.state()).isEqualTo(BuyerCheckoutState.RESERVED);
        CheckoutResponse pending = checkoutPayments.submit(kept.checkoutId());
        assertThat(checkoutPayments.completeApproval(kept.checkoutId(), approvalToken(pending.paymentApprovalUrl()))
                .state()).isEqualTo(BuyerCheckoutState.PAID);
    }

    /** checkout을 {@code held}만큼 일찍 만든 것으로 옮겨, 지금 취소하면 그만큼 잡고 있다가 취소한 것이 되게 한다. */
    private void heldFor(UUID checkoutId, Duration held) {
        jdbc.update("UPDATE buyer_checkout SET created_at = created_at - ? * interval '1 second' WHERE checkout_id = ?",
                held.toSeconds(), checkoutId);
    }

    private Sku sku(String label) {
        return sku(label, STOCK);
    }

    private Sku sku(String label, int stock) {
        UUID sellerId = sellers.createSeller().sellerId();
        var product = products.createProduct(sellerId,
                new ProductRequest.CreateProduct(label + "-" + UUID.randomUUID(), null));
        String skuId = label + "-" + UUID.randomUUID();
        products.registerSku(sellerId, product.productId(), new ProductRequest.RegisterSellerSku(skuId, 1000, stock));
        return new Sku(sellerId, skuId);
    }

    private record Sku(UUID sellerId, String skuId) { }
}
