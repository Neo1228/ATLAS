package com.atlas.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.atlas.admission.dto.request.ConfigureSellerSkuPolicyRequest;
import com.atlas.admission.dto.request.JoinQueueRequest;
import com.atlas.admission.dto.response.QueueTicketResponse;
import com.atlas.admission.service.AdmissionService;
import com.atlas.admission.dto.request.CreateCampaignRequest;
import com.atlas.admission.dto.response.CampaignResponse;
import com.atlas.admission.service.CampaignService;
import com.atlas.catalog.dto.request.CreateProductRequest;
import com.atlas.catalog.dto.request.RegisterSellerSkuRequest;
import com.atlas.catalog.dto.response.ProductResponse;
import com.atlas.catalog.service.ProductService;
import com.atlas.catalog.service.SellerService;
import com.atlas.inventory.dto.response.InventoryPoolResponse;
import com.atlas.inventory.service.InventoryService;
import com.atlas.order.dto.response.OrderResponse;
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
        CampaignResponse campaign = openCampaign(1);
        QueueTicketResponse ticket = join(campaign, "paid-cancel-buyer");
        OrderResponse order = orders.startCheckout(campaign.campaignId(), ticket.ticketId());
        OrderResponse pending = orders.submitPayment(order.orderId());
        OrderResponse paid = orders.completePaymentApproval(order.orderId(),
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
        OrderResponse after = orders.find(order.orderId());
        assertThat(after.state()).isEqualTo(OrderState.PAID);
        assertThat(after.paymentState()).isEqualTo(PaymentState.CAPTURED);
        InventoryPoolResponse stock = inventory.find(campaign.sellerId(), campaign.skuId());
        assertThat(stock.claimed()).isEqualTo(1);
        assertThat(stock.reserved()).isZero();
        assertThat(stock.available()).isZero();
    }

    @Test
    void cancellingAlreadyCancelledOrderStaysIdempotent() {
        CampaignResponse campaign = openCampaign(1);
        QueueTicketResponse ticket = join(campaign, "cancelled-cancel-buyer");
        OrderResponse order = orders.startCheckout(campaign.campaignId(), ticket.ticketId());
        assertThat(order.state()).isEqualTo(OrderState.RESERVED);

        OrderResponse cancelled = orders.cancel(order.orderId(), "cancel-first-" + order.orderId());
        assertThat(cancelled.state()).isEqualTo(OrderState.CANCELLED);

        assertThatCode(() -> orders.cancel(order.orderId(), "cancel-retry-" + order.orderId()))
                .doesNotThrowAnyException();
        assertThat(orders.cancel(order.orderId(), "cancel-first-" + order.orderId())).isEqualTo(cancelled);
        assertThat(orders.find(order.orderId()).state()).isEqualTo(OrderState.CANCELLED);
        InventoryPoolResponse stock = inventory.find(campaign.sellerId(), campaign.skuId());
        assertThat(stock.available()).isEqualTo(1);
        assertThat(stock.reserved()).isZero();
        assertThat(stock.claimed()).isZero();
    }

    private CampaignResponse openCampaign(int initialStock) {
        UUID sellerId = sellers.createSeller().sellerId();
        String skuId = "paid-cancel-sku-" + UUID.randomUUID();
        ProductResponse product = products.createProduct(sellerId,
                new CreateProductRequest("Product " + skuId, null));
        products.registerSku(sellerId, product.productId(), new RegisterSellerSkuRequest(skuId, 100, initialStock));
        admission.configureSellerSkuPolicy(new ConfigureSellerSkuPolicyRequest(sellerId, skuId, 1));
        CampaignResponse campaign = campaigns.create(new CreateCampaignRequest(sellerId, skuId, Duration.ofMinutes(5)));
        return campaigns.open(campaign.campaignId());
    }

    private QueueTicketResponse join(CampaignResponse campaign, String buyerPrefix) {
        return admission.join(new JoinQueueRequest(campaign.campaignId(), campaign.skuId(),
                buyerPrefix + "-" + UUID.randomUUID(), "join-" + UUID.randomUUID()));
    }
}
