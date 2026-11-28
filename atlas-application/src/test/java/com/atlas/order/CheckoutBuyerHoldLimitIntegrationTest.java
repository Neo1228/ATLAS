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
 */
@Testcontainers
@SpringBootTest
class CheckoutBuyerHoldLimitIntegrationTest {
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
