package com.atlas.admission;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.atlas.admission.dto.request.AdmissionRequest;
import com.atlas.admission.dto.response.AdmissionResponse;
import com.atlas.admission.exception.AdmissionException;
import com.atlas.admission.model.CheckoutSlotReleaseReason;
import com.atlas.admission.service.AdmissionService;
import com.atlas.admission.service.CampaignService;
import com.atlas.catalog.dto.request.ProductRequest;
import com.atlas.catalog.dto.response.CatalogResponse;
import com.atlas.catalog.service.ProductService;
import com.atlas.catalog.service.SellerService;
import com.atlas.payment.PayPalIntegrationTestSupport;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
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
 * 같은 Seller-SKU에서 한 구매자의 live ticket은 1개라는 규칙(ADR-0088)을 실제 PostgreSQL에서 확인한다.
 * 규칙이 없으면 서로 다른 Idempotency-Key로 동시에 join한 요청이 모두 ticket과 slot을 잡는다(EXP-092 H7 음성 대조군).
 */
@Testcontainers
@SpringBootTest
class AdmissionLiveTicketIntegrationTest extends PayPalIntegrationTestSupport {
    private static final int CONCURRENT_JOINS = 8;

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18.6");

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry properties) {
        properties.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        properties.add("spring.datasource.username", POSTGRES::getUsername);
        properties.add("spring.datasource.password", POSTGRES::getPassword);
        properties.add("atlas.admission.expiry-poll-interval", () -> "PT24H");
        properties.add("atlas.orders.expiry-poll-interval", () -> "PT24H");
    }

    @Autowired private SellerService sellers;
    @Autowired private ProductService products;
    @Autowired private CampaignService campaigns;
    @Autowired private AdmissionService admission;
    @Autowired private JdbcTemplate jdbc;

    @Test
    void concurrentJoinsByOneBuyerCreateExactlyOneLiveTicket() throws Exception {
        Queue queue = openQueue();
        ExecutorService pool = Executors.newFixedThreadPool(CONCURRENT_JOINS);
        List<String> outcomes = new ArrayList<>();
        try {
            CountDownLatch go = new CountDownLatch(1);
            List<Future<String>> results = new ArrayList<>();
            for (int i = 0; i < CONCURRENT_JOINS; i++) {
                results.add(pool.submit(() -> {
                    go.await();
                    try {
                        join(queue, "racer", "key-" + UUID.randomUUID());
                        return "ok";
                    } catch (AdmissionException rejected) {
                        return rejected.code().name();
                    }
                }));
            }
            go.countDown();
            for (Future<String> result : results) {
                outcomes.add(result.get(60, TimeUnit.SECONDS));
            }
        } finally {
            pool.shutdownNow();
        }

        assertThat(liveTickets(queue, "racer")).isEqualTo(1);
        assertThat(outcomes).containsOnly("ok", "LIVE_TICKET_LIMIT_REACHED");
        assertThat(outcomes.stream().filter("ok"::equals).count()).isEqualTo(1);
    }

    @Test
    void replayingTheSameIdempotencyKeyReturnsTheLiveTicketInsteadOfRejecting() {
        Queue queue = openQueue();
        AdmissionResponse.QueueTicket first = join(queue, "buyer", "same-key");

        AdmissionResponse.QueueTicket replay = join(queue, "buyer", "same-key");

        assertThat(replay.ticketId()).isEqualTo(first.ticketId());
        assertThatThrownBy(() -> join(queue, "buyer", "other-key"))
                .isInstanceOfSatisfying(AdmissionException.class,
                        rejected -> assertThat(rejected.code()).isEqualTo(AdmissionException.Code.LIVE_TICKET_LIMIT_REACHED));
        assertThat(join(queue, "another-buyer", "another-key").ticketId()).isNotEqualTo(first.ticketId());
    }

    @Test
    void buyerCanJoinAgainOnceTheEarlierTicketIsNoLongerLive() {
        Queue queue = openQueue();
        AdmissionResponse.QueueTicket first = join(queue, "buyer", "first-key");
        admission.releaseCheckoutSlot(queue.campaignId(), first.ticketId(), CheckoutSlotReleaseReason.BUYER_CANCELLED);

        AdmissionResponse.QueueTicket second = join(queue, "buyer", "second-key");

        assertThat(second.ticketId()).isNotEqualTo(first.ticketId());
        assertThat(liveTickets(queue, "buyer")).isEqualTo(1);
    }

    private long liveTickets(Queue queue, String buyer) {
        Long count = jdbc.queryForObject("""
                SELECT count(*) FROM admission_ticket WHERE seller_id = ? AND sku_id = ? AND buyer_ref = ?
                  AND status IN ('WAITING', 'CHECKOUT_ACTIVE', 'CHECKOUT_STARTED')
                """, Long.class, queue.sellerId(), queue.skuId(), buyer);
        return count == null ? 0 : count;
    }

    private AdmissionResponse.QueueTicket join(Queue queue, String buyer, String idempotencyKey) {
        return admission.join(new AdmissionRequest.JoinQueue(queue.campaignId(), queue.skuId(), buyer, idempotencyKey));
    }

    private Queue openQueue() {
        CatalogResponse.Seller seller = sellers.createSeller();
        CatalogResponse.Product product = products.createProduct(seller.sellerId(),
                new ProductRequest.CreateProduct("live ticket " + UUID.randomUUID(), "description"));
        String skuId = "live-ticket-" + UUID.randomUUID();
        products.registerSku(seller.sellerId(), product.productId(), new ProductRequest.RegisterSellerSku(skuId, 100, 5));
        admission.configureSellerSkuPolicy(new AdmissionRequest.ConfigureSellerSkuPolicy(seller.sellerId(), skuId, 20));
        AdmissionResponse.Campaign campaign = campaigns.create(
                new AdmissionRequest.CreateCampaign(seller.sellerId(), skuId, null));
        campaigns.open(campaign.campaignId());
        return new Queue(seller.sellerId(), skuId, campaign.campaignId());
    }

    private record Queue(UUID sellerId, String skuId, UUID campaignId) {}
}
