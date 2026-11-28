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
 * {@code INVENTORY_UNAVAILABLE}을 받고, 만료 뒤 같은 일을 되풀이할 수 있었다.
 */
@Testcontainers
@SpringBootTest
class CheckoutBuyerHoldLimitIntegrationTest {
    private static final int STOCK = 5;
    private static final int RESERVED_UNIT_LIMIT = 3;

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18.6");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("atlas.admission.expiry-poll-interval", () -> "PT24H");
        registry.add("atlas.orders.expiry-poll-interval", () -> "PT24H");
        registry.add("atlas.checkout-buyer-limit.reserved-units", () -> String.valueOf(RESERVED_UNIT_LIMIT));
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
        Sku sku = sku("hoard");
        String hoarder = "hoarder-" + UUID.randomUUID();
        carts.setItemQuantity(sku.sellerId(), hoarder, sku.skuId(), STOCK);

        assertThatThrownBy(() -> checkouts.checkout(hoarder, "hoard-" + UUID.randomUUID()))
                .isInstanceOfSatisfying(OrderException.class,
                        rejected -> assertThat(rejected.code()).isEqualTo(OrderException.Code.INVALID_REQUEST));
        assertThat(inventory.find(sku.sellerId(), sku.skuId()).reserved()).isZero();
        String other = "other-buyer-" + UUID.randomUUID();
        carts.setItemQuantity(sku.sellerId(), other, sku.skuId(), 1);
        assertThat(checkouts.checkout(other, "other-" + UUID.randomUUID()).state())
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
        jdbc.update("UPDATE purchase_order SET checkout_expires_at = now() - interval '1 second' WHERE checkout_id = ?",
                afterCancel.checkoutId());
        carts.setItemQuantity(sku.sellerId(), buyer, sku.skuId(), 1);
        assertThat(checkouts.checkout(buyer, "after-expiry-" + UUID.randomUUID()).state())
                .isEqualTo(BuyerCheckoutState.RESERVED);
    }

    private Sku sku(String label) {
        UUID sellerId = sellers.createSeller().sellerId();
        var product = products.createProduct(sellerId,
                new ProductRequest.CreateProduct(label + "-" + UUID.randomUUID(), null));
        String skuId = label + "-" + UUID.randomUUID();
        products.registerSku(sellerId, product.productId(), new ProductRequest.RegisterSellerSku(skuId, 1000, STOCK));
        return new Sku(sellerId, skuId);
    }

    private record Sku(UUID sellerId, String skuId) { }
}
