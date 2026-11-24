package com.atlas.payment;

import static org.assertj.core.api.Assertions.assertThat;

import com.atlas.admission.dto.request.ConfigureSellerSkuPolicyRequest;
import com.atlas.admission.dto.request.JoinQueueRequest;
import com.atlas.admission.dto.response.QueueTicketResponse;
import com.atlas.admission.service.AdmissionService;
import com.atlas.admission.dto.request.CreateCampaignRequest;
import com.atlas.admission.dto.response.CampaignResponse;
import com.atlas.admission.service.CampaignService;
import com.atlas.catalog.dto.request.CreateProductRequest;
import com.atlas.catalog.dto.request.RegisterSellerSkuRequest;
import com.atlas.catalog.service.ProductService;
import com.atlas.catalog.service.SellerService;
import com.atlas.order.dto.response.OrderResponse;
import com.atlas.order.service.OrderService;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * 같은 주문에 동시 결제 제출이 몰려도 결제 시도와 provider 호출이 1회인지 고정한다.
 *
 * <p>프로덕션 replica 가 2대 이상이면 {@code OwnerLockRegistry}(JVM 전역 synchronized)가 두
 * 요청을 직렬화하지 못한다. 이 테스트는 그 상황에서 DB 가 최종 방어선으로 충분한지 확인한다.
 *
 * <p>주의: provider 호출 수는 <b>경로별</b>로 세야 한다. 이전에 {@code takeRequest} 로 큐 전체를
 * 세었더니 OAuth 토큰 요청까지 포함되어 "create-order 2회"로 잘못 읽혔다. 실제로는
 * create-order 1회 + token 1회였다. 이 테스트가 그 오독을 막는다.
 */
@Testcontainers
@SpringBootTest
@DirtiesContext
class CrossInstancePaymentRaceTest extends PayPalIntegrationTestSupport {
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

    @Test
    void concurrentPaymentSubmissionCreatesOneAttemptAndOneProviderOrder() throws Exception {
        UUID orderId = createReservedOrder();
        int racers = 4;
        CountDownLatch start = new CountDownLatch(1);
        List<Future<OrderResponse>> futures = new ArrayList<>();
        try (var executor = Executors.newFixedThreadPool(racers)) {
            for (int index = 0; index < racers; index++) {
                Callable<OrderResponse> task = () -> {
                    start.await();
                    return orders.submitPayment(orderId);
                };
                futures.add(executor.submit(task));
            }
            start.countDown();
            for (Future<OrderResponse> future : futures) {
                assertThat(future.get(30, TimeUnit.SECONDS).orderId()).isEqualTo(orderId);
            }
        }

        // DB 는 attempt 를 주문당 1개로 제한한다(payment_attempt_order_id_unique).
        // 이 값이 1 이면 중복 상태 전이는 DB 가 막았다는 뜻이다.
        assertThat(jdbcAttemptCount(orderId)).isEqualTo(1);
        // provider 측 create-order 요청 수를 센다. 고정 request-id 로 접히면 중복 호출이
        // 상태를 바꾸지 않지만, 호출 자체는 늘어날 수 있다.
        Map<String, Integer> calls = providerCalls();
        int providerOrders = calls.entrySet().stream()
                .filter(entry -> entry.getKey().startsWith("POST /v2/checkout/orders"))
                .mapToInt(entry -> entry.getValue()).sum();
        System.out.printf("RACE_ATTEMPTS orderId=%s attempts=%d calls=%s%n",
                orderId, jdbcAttemptCount(orderId), calls);
        int providerOrdersFinal = providerOrders;
        // fence 가 provider 호출을 1회로 접어야 한다. 2회 이상이면 동시 요청이 각각 provider 를
        // 호출한 것이고, replica 간 중복 호출이 실제로 발생한다는 뜻이다.
        assertThat(providerOrdersFinal)
                .as("provider create-order calls for one order under concurrency; calls=%s", calls)
                .isEqualTo(1);
    }

    private int jdbcAttemptCount(UUID orderId) {
        Integer count = jdbc.queryForObject(
                "SELECT count(*) FROM payment_attempt WHERE order_id = ?", Integer.class, orderId);
        return count == null ? 0 : count;
    }

    @Autowired
    private JdbcTemplate jdbc;

    private UUID createReservedOrder() {
        UUID sellerId = sellers.createSeller().sellerId();
        String skuId = "race-" + UUID.randomUUID();
        var product = products.createProduct(sellerId, new CreateProductRequest("Race product", null));
        products.registerSku(sellerId, product.productId(), new RegisterSellerSkuRequest(skuId, 17_200, 10));
        admission.configureSellerSkuPolicy(new ConfigureSellerSkuPolicyRequest(sellerId, skuId, 5));
        CampaignResponse campaign = campaigns.open(campaigns.create(
                new CreateCampaignRequest(sellerId, skuId, Duration.ofMinutes(5))).campaignId());
        QueueTicketResponse ticket = admission.join(new JoinQueueRequest(campaign.campaignId(),
                campaign.skuId(), "race-buyer-" + UUID.randomUUID(), "race-key-" + UUID.randomUUID()));
        return orders.startCheckout(campaign.campaignId(), ticket.ticketId()).orderId();
    }
}
