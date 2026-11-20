package com.atlas.order;

import static org.assertj.core.api.Assertions.assertThat;

import com.atlas.cart.service.CartService;
import com.atlas.catalog.dto.request.CreateProductRequest;
import com.atlas.catalog.dto.request.RegisterSellerSkuRequest;
import com.atlas.catalog.service.ProductService;
import com.atlas.catalog.service.SellerService;
import com.atlas.order.dto.response.CheckoutResponse;
import com.atlas.order.dto.response.OrderHistoryPageResponse;
import com.atlas.order.dto.response.OrderItemResponse;
import com.atlas.order.service.CartCheckoutService;
import com.atlas.order.service.OrderService;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * 판매자가 HTTP 응답만으로 자기 주문과 주문 항목을 식별할 수 있는지 검증한다.
 *
 * <p>왜 필요한가: 배송·환불·반품 검수는 모두 {@code orderItemId} 를 요구하고, 경로는
 * {@code /sellers/{sellerId}/orders/{orderId}/...} 형태다. 두 식별자가 응답에 없으면 판매자는
 * 첫 이행 호출조차 만들 수 없다. 이 저장소의 통합 테스트들이 JDBC 로 우회하던 사실이 그 증거다.
 */
@Testcontainers
@SpringBootTest
class SellerOrderDiscoveryIntegrationTest {
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
        registry.add("atlas.admission.expiry-poll-interval", () -> "PT24H");
        registry.add("atlas.orders.expiry-poll-interval", () -> "PT24H");
    }

    @Autowired private SellerService sellers;
    @Autowired private ProductService products;
    @Autowired private CartService carts;
    @Autowired private CartCheckoutService checkouts;
    @Autowired private OrderService orders;

    /**
     * 판매자 주문 목록 API 로 orderId 를 발견하고, 주문 상세에서 orderItemId 를 얻는다.
     */
    @Test
    void sellerDiscoversOrderAndItemIdsFromResponsesAlone() {
        UUID sellerId = sellers.createSeller().sellerId();
        var product = products.createProduct(sellerId,
                new CreateProductRequest("seller-discovery-" + UUID.randomUUID(), null));
        String skuId = "seller-discovery-" + UUID.randomUUID();
        products.registerSku(sellerId, product.productId(), new RegisterSellerSkuRequest(skuId, 1000, 5));

        String buyerRef = "seller-discovery-buyer-" + UUID.randomUUID();
        carts.setItemQuantity(sellerId, buyerRef, skuId, 1);
        CheckoutResponse checkout = checkouts.checkout(buyerRef, "seller-discovery-" + UUID.randomUUID());
        UUID expectedOrderId = checkout.orders().getFirst().orderId();

        // 1) 판매자 주문 목록으로 orderId 를 발견할 수 있어야 한다.
        OrderHistoryPageResponse page = orders.findSellerOrderHistory(sellerId, 20, null);
        assertThat(page.orders())
                .as("판매자 목록에 방금 만든 주문이 보여야 한다")
                .anyMatch(entry -> entry.orderId().equals(expectedOrderId));

        // 2) 주문 상세의 항목에서 orderItemId 를 얻을 수 있어야 한다.
        var detail = orders.find(expectedOrderId);
        assertThat(detail.items()).as("주문 항목이 있어야 한다").isNotEmpty();
        for (OrderItemResponse item : detail.items()) {
            assertThat(item.orderItemId())
                    .as("이행 API 가 요구하는 orderItemId 가 응답에 있어야 한다")
                    .isNotNull();
        }
    }

    /**
     * 판매자 목록은 자기 주문만 보여야 한다.
     */
    @Test
    void sellerHistoryContainsOnlyThatSellersOrders() {
        UUID sellerA = sellers.createSeller().sellerId();
        UUID sellerB = sellers.createSeller().sellerId();
        var productA = products.createProduct(sellerA,
                new CreateProductRequest("seller-a-" + UUID.randomUUID(), null));
        String skuA = "seller-a-" + UUID.randomUUID();
        products.registerSku(sellerA, productA.productId(), new RegisterSellerSkuRequest(skuA, 500, 5));

        String buyerRef = "isolation-buyer-" + UUID.randomUUID();
        carts.setItemQuantity(sellerA, buyerRef, skuA, 1);
        checkouts.checkout(buyerRef, "isolation-" + UUID.randomUUID());

        OrderHistoryPageResponse sellerBPage = orders.findSellerOrderHistory(sellerB, 20, null);
        assertThat(sellerBPage.orders())
                .as("다른 판매자의 주문이 섞이면 안 된다")
                .allMatch(entry -> entry.sellerId().equals(sellerB));
    }
}
