package com.atlas.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.atlas.order.dto.response.OrderViewResponse;
import com.atlas.inventory.dto.response.InventoryResponse;
import com.atlas.catalog.dto.response.CatalogResponse;
import com.atlas.catalog.dto.request.ProductRequest;
import com.atlas.admission.dto.response.AdmissionResponse;
import com.atlas.admission.dto.request.AdmissionRequest;
import com.atlas.admission.service.AdmissionService;
import com.atlas.admission.service.CampaignService;
import com.atlas.catalog.service.ProductService;
import com.atlas.catalog.service.SellerService;
import com.atlas.inventory.service.InventoryService;
import com.atlas.order.exception.OrderException;
import com.atlas.order.model.OrderState;
import com.atlas.order.service.OrderService;
import com.atlas.payment.PayPalIntegrationTestSupport;
import com.atlas.payment.model.PaymentState;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/** 단일 주문 취소 API 가 결제 완료 주문에 성공처럼 응답하지 않는 계약을 검증합니다. */
@Testcontainers
@AutoConfigureMockMvc(addFilters = false)
@SpringBootTest
class PaidOrderCancelContractIntegrationTest extends PayPalIntegrationTestSupport {
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
        properties.add("atlas.admission.expiry-poll-interval", () -> "PT24H");
        properties.add("atlas.orders.expiry-poll-interval", () -> "PT24H");
    }

    @Autowired private MockMvc mockMvc;
    @Autowired private SellerService sellers;
    @Autowired private ProductService products;
    @Autowired private InventoryService inventory;
    @Autowired private CampaignService campaigns;
    @Autowired private AdmissionService admission;
    @Autowired private OrderService orders;

    @Test
    void cancellingPaidCampaignOrderIsRejectedAndLeavesOrderPaid() throws Exception {
        AdmissionResponse.Campaign campaign = openCampaign(1);
        AdmissionResponse.QueueTicket ticket = join(campaign, "paid-cancel-buyer");
        OrderViewResponse.Order order = orders.startCheckout(campaign.campaignId(), ticket.ticketId());
        OrderViewResponse.Order pending = orders.submitPayment(order.orderId());
        OrderViewResponse.Order paid = orders.completePaymentApproval(order.orderId(),
                approvalToken(pending.paymentApprovalUrl()));
        assertThat(paid.state()).isEqualTo(OrderState.PAID);
        assertThat(paid.paymentState()).isEqualTo(PaymentState.CAPTURED);

        // 서비스 계약: 결제 완료 주문 취소는 무변경 성공이 아니라 CONFLICT 다.
        assertThatThrownBy(() -> orders.cancel(order.orderId(), "paid-cancel-" + order.orderId()))
                .isInstanceOfSatisfying(OrderException.class, exception -> {
                    assertThat(exception.code()).isEqualTo(OrderException.Code.CONFLICT);
                    assertThat(exception.getMessage()).contains("refund");
                });

        // HTTP 계약: 구매자는 200 이 아니라 409 CONFLICT 를 받는다.
        mockMvc.perform(post("/api/orders/{orderId}/cancel", order.orderId())
                        .header("Idempotency-Key", "paid-cancel-http-" + order.orderId()))
                .andExpect(status().isConflict())
                .andExpect(result -> assertThat(result.getResponse().getContentAsString())
                        .contains("\"code\":\"CONFLICT\""));

        // 거절된 취소는 주문·결제·재고 어느 것도 바꾸지 않는다.
        OrderViewResponse.Order after = orders.find(order.orderId());
        assertThat(after.state()).isEqualTo(OrderState.PAID);
        assertThat(after.paymentState()).isEqualTo(PaymentState.CAPTURED);
        InventoryResponse.Pool stock = inventory.find(campaign.sellerId(), campaign.skuId());
        assertThat(stock.claimed()).isEqualTo(1);
        assertThat(stock.reserved()).isZero();
        assertThat(stock.available()).isZero();
    }

    @Test
    void cancellingAlreadyCancelledOrderStaysIdempotent() {
        AdmissionResponse.Campaign campaign = openCampaign(1);
        AdmissionResponse.QueueTicket ticket = join(campaign, "cancelled-cancel-buyer");
        OrderViewResponse.Order order = orders.startCheckout(campaign.campaignId(), ticket.ticketId());
        assertThat(order.state()).isEqualTo(OrderState.RESERVED);

        OrderViewResponse.Order cancelled = orders.cancel(order.orderId(), "cancel-first-" + order.orderId());
        assertThat(cancelled.state()).isEqualTo(OrderState.CANCELLED);

        assertThatCode(() -> orders.cancel(order.orderId(), "cancel-retry-" + order.orderId()))
                .doesNotThrowAnyException();
        assertThat(orders.cancel(order.orderId(), "cancel-first-" + order.orderId())).isEqualTo(cancelled);
        assertThat(orders.find(order.orderId()).state()).isEqualTo(OrderState.CANCELLED);
        InventoryResponse.Pool stock = inventory.find(campaign.sellerId(), campaign.skuId());
        assertThat(stock.available()).isEqualTo(1);
        assertThat(stock.reserved()).isZero();
        assertThat(stock.claimed()).isZero();
    }

    private AdmissionResponse.Campaign openCampaign(int initialStock) {
        UUID sellerId = sellers.createSeller().sellerId();
        String skuId = "paid-cancel-sku-" + UUID.randomUUID();
        CatalogResponse.Product product = products.createProduct(sellerId,
                new ProductRequest.CreateProduct("Product " + skuId, null));
        products.registerSku(sellerId, product.productId(), new ProductRequest.RegisterSellerSku(skuId, 100, initialStock));
        admission.configureSellerSkuPolicy(new AdmissionRequest.ConfigureSellerSkuPolicy(sellerId, skuId, 1));
        AdmissionResponse.Campaign campaign = campaigns.create(new AdmissionRequest.CreateCampaign(sellerId, skuId, Duration.ofMinutes(5)));
        return campaigns.open(campaign.campaignId());
    }

    private AdmissionResponse.QueueTicket join(AdmissionResponse.Campaign campaign, String buyerPrefix) {
        return admission.join(new AdmissionRequest.JoinQueue(campaign.campaignId(), campaign.skuId(),
                buyerPrefix + "-" + UUID.randomUUID(), "join-" + UUID.randomUUID()));
    }
}
