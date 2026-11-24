package com.atlas.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.atlas.order.dto.response.OrderViewResponse;
import com.atlas.catalog.dto.request.ProductRequest;
import com.atlas.account.service.AccountService;
import com.atlas.order.service.CartService;
import com.atlas.catalog.service.ProductService;
import com.atlas.catalog.service.SellerService;
import com.atlas.order.dto.response.CheckoutResponse;
import com.atlas.order.service.CartCheckoutService;
import com.atlas.order.service.OrderService;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.ObjectMapper;

/**
 * 판매자가 HTTP 응답만으로 자기 주문과 주문 항목을 식별할 수 있는지 검증한다.
 *
 * <p>왜 필요한가: 배송·환불·반품 검수는 모두 {@code orderItemId} 를 요구하고, 경로는
 * {@code /sellers/{sellerId}/orders/{orderId}/...} 형태다. 두 식별자가 응답에 없으면 판매자는
 * 첫 이행 호출조차 만들 수 없다. 이 저장소의 통합 테스트들이 JDBC 로 우회하던 사실이 그 증거다.
 */
@Testcontainers
@SpringBootTest
@AutoConfigureMockMvc
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
    @Autowired private AccountService accounts;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;

    /** 판매자 상세 API 가 배송·환불에 필요한 주문 항목 식별자를 제공하는지 검증한다. */
    @Test
    void sellerDiscoversOrderAndItemIdsThroughSellerScopedHttpResponses() throws Exception {
        UUID sellerId = sellers.createSeller().sellerId();
        var product = products.createProduct(sellerId,
                new ProductRequest.CreateProduct("seller-discovery-" + UUID.randomUUID(), null));
        String skuId = "seller-discovery-" + UUID.randomUUID();
        products.registerSku(sellerId, product.productId(), new ProductRequest.RegisterSellerSku(skuId, 1000, 5));

        String buyerRef = "seller-discovery-buyer-" + UUID.randomUUID();
        carts.setItemQuantity(sellerId, buyerRef, skuId, 1);
        CheckoutResponse checkout = checkouts.checkout(buyerRef, "seller-discovery-" + UUID.randomUUID());
        UUID expectedOrderId = checkout.orders().getFirst().orderId();
        UUID sellerAccountId = createSellerAccount(sellerId);

        String listJson = mockMvc.perform(get("/api/sellers/{sellerId}/orders", sellerId)
                        .with(jwt().jwt(token -> token.subject(sellerAccountId.toString()))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        OrderViewResponse.HistoryPage page = objectMapper.readValue(listJson, OrderViewResponse.HistoryPage.class);
        assertThat(page.orders()).anyMatch(entry -> entry.orderId().equals(expectedOrderId));

        String detailJson = mockMvc.perform(get("/api/sellers/{sellerId}/orders/{orderId}", sellerId, expectedOrderId)
                        .with(jwt().jwt(token -> token.subject(sellerAccountId.toString()))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        OrderViewResponse.SellerOrderDetail detail = objectMapper.readValue(detailJson, OrderViewResponse.SellerOrderDetail.class);
        assertThat(detail.orderId()).isEqualTo(expectedOrderId);
        assertThat(detail.items()).singleElement().satisfies(item -> {
            assertThat(item.orderItemId()).isNotNull();
            assertThat(item.sellerId()).isEqualTo(sellerId);
        });
        assertThat(detailJson).doesNotContain("paymentAttemptId", "paymentApprovalUrl", "buyerRef");

        UUID otherSellerId = sellers.createSeller().sellerId();
        UUID otherAccountId = createSellerAccount(otherSellerId);
        mockMvc.perform(get("/api/sellers/{sellerId}/orders/{orderId}", otherSellerId, expectedOrderId)
                        .with(jwt().jwt(token -> token.subject(otherAccountId.toString()))))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/orders/{orderId}", expectedOrderId)
                        .with(jwt().jwt(token -> token.subject(sellerAccountId.toString()))))
                .andExpect(status().isForbidden());
    }

    private UUID createSellerAccount(UUID sellerId) {
        UUID accountId = UUID.randomUUID();
        jdbc.update("INSERT INTO user_account (account_id, email, password_hash, email_verified_at) VALUES (?, ?, ?, now())",
                accountId, accountId + "@example.test", "integration-test-password-hash");
        accounts.linkSeller(accountId, sellerId);
        return accountId;
    }

    /**
     * 판매자 목록은 자기 주문만 보여야 한다.
     */
    @Test
    void sellerHistoryContainsOnlyThatSellersOrders() {
        UUID sellerA = sellers.createSeller().sellerId();
        UUID sellerB = sellers.createSeller().sellerId();
        var productA = products.createProduct(sellerA,
                new ProductRequest.CreateProduct("seller-a-" + UUID.randomUUID(), null));
        String skuA = "seller-a-" + UUID.randomUUID();
        products.registerSku(sellerA, productA.productId(), new ProductRequest.RegisterSellerSku(skuA, 500, 5));

        String buyerRef = "isolation-buyer-" + UUID.randomUUID();
        carts.setItemQuantity(sellerA, buyerRef, skuA, 1);
        checkouts.checkout(buyerRef, "isolation-" + UUID.randomUUID());

        OrderViewResponse.HistoryPage sellerBPage = orders.findSellerOrderHistory(sellerB, 20, null);
        assertThat(sellerBPage.orders())
                .as("다른 판매자의 주문이 섞이면 안 된다")
                .allMatch(entry -> entry.sellerId().equals(sellerB));
    }
}
