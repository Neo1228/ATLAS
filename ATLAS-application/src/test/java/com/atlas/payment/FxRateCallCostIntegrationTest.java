package com.atlas.payment;

import static org.assertj.core.api.Assertions.assertThat;

import com.atlas.admission.dto.request.ConfigureSellerSkuPolicyRequest;
import com.atlas.admission.dto.request.JoinQueueRequest;
import com.atlas.admission.dto.response.QueueTicketResponse;
import com.atlas.admission.service.AdmissionService;
import com.atlas.campaign.dto.request.CreateCampaignRequest;
import com.atlas.campaign.dto.response.CampaignResponse;
import com.atlas.campaign.service.CampaignService;
import com.atlas.catalog.dto.request.CreateProductRequest;
import com.atlas.catalog.dto.request.RegisterSellerSkuRequest;
import com.atlas.catalog.dto.response.ProductResponse;
import com.atlas.catalog.service.ProductService;
import com.atlas.catalog.service.SellerService;
import com.atlas.order.dto.response.OrderResponse;
import com.atlas.order.service.OrderService;
import com.atlas.payment.provider.exchange.CachingExchangeRateProvider;
import com.atlas.payment.service.FxConversionService;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * 결제 임계 경로에서 외부 환율 API 를 몇 번 호출하는지 센다.
 *
 * <p>이 테스트는 성능을 주장하지 않는다. "결제 1건이 외부 환율 호출을 만든다"는 구조적
 * 사실만 고정한다. 지연 영향은 같은 조건에서 stub 지연을 바꿔 별도로 측정한다.
 */
@Testcontainers
@SpringBootTest
@DirtiesContext
class FxRateCallCostIntegrationTest extends PayPalIntegrationTestSupport {
    @Container
    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18.6")
            .withDatabaseName("atlas");

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
    }

    @Autowired private OrderService orders;
    @Autowired private AdmissionService admission;
    @Autowired private CampaignService campaigns;
    @Autowired private SellerService sellers;
    @Autowired private ProductService products;
    @Autowired private FxConversionService fx;
    @Autowired private CachingExchangeRateProvider fxCache;

    @BeforeEach
    void resetFxCache() {
        // 같은 Spring context 를 공유하므로 이전 테스트가 데운 캐시를 비운다.
        fxCache.clear();
    }

    @Test
    void repeatedPaymentsReuseASingleExchangeRateCallWithinTheTtl() {
        int payments = 3;
        long before = exchangeRateCallCount();

        for (int index = 0; index < payments; index++) {
            OrderResponse pending = orders.submitPayment(createOrder());
            assertThat(pending.paymentApprovalUrl()).isNotBlank();
        }

        // 결제 3건이 TTL 안에서 외부 호출 1회로 접힌다. 이 값이 payments 로 돌아가면
        // 캐시가 동작하지 않는 것이다(회귀).
        assertThat(exchangeRateCallCount() - before)
                .as("FX API calls for 3 payments within the 60s TTL")
                .isEqualTo(1);
    }

    @Test
    void identicalAmountConversionsReuseTheCachedQuote() {
        long before = exchangeRateCallCount();
        List<Long> cents = new ArrayList<>();
        for (int index = 0; index < 5; index++) {
            cents.add(fx.convertKrwToUsdCents(17_200L).usdCents());
        }

        // 같은 금액·같은 시점의 변환은 같은 고시값을 쓰므로 네트워크를 다시 타지 않는다.
        assertThat(cents).containsOnly(cents.getFirst());
        assertThat(exchangeRateCallCount() - before)
                .as("network calls for 5 identical conversions")
                .isEqualTo(1);
    }

    private long exchangeRateCallCount() {
        return exchangeRateRequestCount();
    }

    private UUID createOrder() {
        UUID sellerId = sellers.createSeller().sellerId();
        String skuId = "fx-cost-" + UUID.randomUUID();
        ProductResponse product = products.createProduct(sellerId,
                new CreateProductRequest("FX cost probe", null));
        products.registerSku(sellerId, product.productId(),
                new RegisterSellerSkuRequest(skuId, 17_200, 10));
        admission.configureSellerSkuPolicy(new ConfigureSellerSkuPolicyRequest(sellerId, skuId, 5));
        CampaignResponse campaign = campaigns.open(
                campaigns.create(new CreateCampaignRequest(sellerId, skuId, Duration.ofMinutes(5))).campaignId());
        QueueTicketResponse ticket = admission.join(new JoinQueueRequest(campaign.campaignId(), campaign.skuId(),
                "fx-buyer-" + UUID.randomUUID(), "fx-key-" + UUID.randomUUID()));
        return orders.startCheckout(campaign.campaignId(), ticket.ticketId()).orderId();
    }
}
