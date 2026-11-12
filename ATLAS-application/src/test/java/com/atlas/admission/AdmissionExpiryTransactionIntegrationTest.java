package com.atlas.admission;

import static org.assertj.core.api.Assertions.assertThat;

import com.atlas.PostgresLockWaitProbe;
import com.atlas.admission.dto.request.ConfigureSellerSkuPolicyRequest;
import com.atlas.admission.dto.request.JoinQueueRequest;
import com.atlas.admission.dto.response.QueueTicketResponse;
import com.atlas.admission.persistence.state.TicketStatus;
import com.atlas.admission.repository.JdbcQueueRepository;
import com.atlas.admission.repository.TicketRepository;
import com.atlas.admission.service.AdmissionService;
import com.atlas.admission.service.impl.AdmissionServiceImpl;
import com.atlas.campaign.dto.request.CreateCampaignRequest;
import com.atlas.campaign.dto.response.CampaignResponse;
import com.atlas.campaign.service.CampaignService;
import com.atlas.catalog.dto.request.CreateProductRequest;
import com.atlas.catalog.dto.request.RegisterSellerSkuRequest;
import com.atlas.catalog.dto.response.ProductResponse;
import com.atlas.catalog.dto.response.SellerResponse;
import com.atlas.catalog.service.ProductService;
import com.atlas.catalog.service.SellerService;
import com.atlas.payment.PayPalIntegrationTestSupport;
import java.sql.Connection;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestReporter;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@Testcontainers
@SpringBootTest
class AdmissionExpiryTransactionIntegrationTest extends PayPalIntegrationTestSupport {
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
    @Autowired private AdmissionServiceImpl admissionService;
    @Autowired private JdbcQueueRepository queueRepository;
    @Autowired private TicketRepository tickets;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private DataSource dataSource;
    @Autowired private PlatformTransactionManager transactionManager;

    @Test
    void commitsOneSellerSkuExpiryBeforeWaitingOnTheNextCounterLock(TestReporter reporter) throws Exception {
        SellerResponse seller = sellers.createSeller();
        ProductResponse product = products.createProduct(seller.sellerId(),
                new CreateProductRequest("admission expiry " + UUID.randomUUID(), "description"));
        DueTicket first = createDueTicket(seller.sellerId(), product, "first");
        DueTicket second = createDueTicket(seller.sellerId(), product, "second");

        List<String> skuOrder = tickets.findDueSkuIds(seller.sellerId(),
                List.of(TicketStatus.WAITING, TicketStatus.CHECKOUT_ACTIVE), Instant.now());
        assertThat(skuOrder).containsExactlyInAnyOrder(first.skuId(), second.skuId());
        DueTicket visibleBeforeRelease = Map.of(first.skuId(), first, second.skuId(), second)
                .get(skuOrder.get(0));
        DueTicket blocked = Map.of(first.skuId(), first, second.skuId(), second)
                .get(skuOrder.get(1));

        CountDownLatch counterLocked = new CountDownLatch(1);
        CountDownLatch releaseCounter = new CountDownLatch(1);
        AtomicInteger blockerPid = new AtomicInteger(-1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<?> lockHolder = executor.submit(() -> new TransactionTemplate(transactionManager)
                    .executeWithoutResult(status -> {
                        if (!queueRepository.lockCounter(seller.sellerId(), blocked.skuId())) {
                            throw new IllegalStateException("Admission counter missing for regression fixture");
                        }
                        blockerPid.set(jdbc.queryForObject("SELECT pg_backend_pid()", Integer.class));
                        counterLocked.countDown();
                        awaitLatch(releaseCounter);
                    }));
            assertThat(counterLocked.await(5, TimeUnit.SECONDS)).isTrue();

            Future<Integer> expiry = executor.submit(admissionService::expireDueTickets);
            assertThat(awaitExpired(visibleBeforeRelease, Duration.ofSeconds(10)))
                    .as("the first seller-SKU expiry commits while the next counter is locked")
                    .isTrue();

            try (Connection observer = dataSource.getConnection()) {
                int observerPid = PostgresLockWaitProbe.backendPid(observer);
                PostgresLockWaitProbe.Observation observation = PostgresLockWaitProbe.awaitWaiter(
                        observer, blockerPid.get(), observerPid, Duration.ofSeconds(10), Duration.ofMillis(100));
                assertThat(observation.queryElapsedMillis()).isPositive();
                reporter.publishEntry("admission.expiry.counter.queryElapsedAtObservationMs",
                        Double.toString(observation.queryElapsedMillis()));
                reporter.publishEntry("admission.expiry.counter.waitEvent", observation.waitEvent());
                reporter.publishEntry("admission.expiry.counter.pollIntervalMs", "100");
            }
            releaseCounter.countDown();
            assertThat(expiry.get(10, TimeUnit.SECONDS)).isEqualTo(2);
            assertThat(lockHolder.get(10, TimeUnit.SECONDS)).isNull();
            assertThat(admission.find(blocked.ticket().campaignId(), blocked.ticket().ticketId()).status())
                    .isEqualTo(TicketStatus.EXPIRED);
        } finally {
            releaseCounter.countDown();
            executor.shutdownNow();
            executor.awaitTermination(5, TimeUnit.SECONDS);
        }
    }


    private DueTicket createDueTicket(UUID sellerId, ProductResponse product, String label) {
        String skuId = "admission-expiry-" + label + "-" + UUID.randomUUID();
        products.registerSku(sellerId, product.productId(), new RegisterSellerSkuRequest(skuId, 100, 5));
        admission.configureSellerSkuPolicy(new ConfigureSellerSkuPolicyRequest(sellerId, skuId, 1));
        CampaignResponse campaign = campaigns.create(new CreateCampaignRequest(
                sellerId, skuId, Duration.ofMinutes(5)));
        campaigns.open(campaign.campaignId());
        QueueTicketResponse ticket = admission.join(new JoinQueueRequest(
                campaign.campaignId(), skuId, "buyer-" + UUID.randomUUID(), "expiry-" + UUID.randomUUID()));
        jdbc.update("UPDATE admission_ticket SET expires_at = ? WHERE ticket_id = ?",
                Timestamp.from(Instant.now().minus(Duration.ofHours(1))), ticket.ticketId());
        return new DueTicket(skuId, ticket);
    }

    private boolean awaitExpired(DueTicket dueTicket, Duration timeout) throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        do {
            if (admission.find(dueTicket.ticket().campaignId(), dueTicket.ticket().ticketId()).status()
                    == TicketStatus.EXPIRED) {
                return true;
            }
            Thread.sleep(25);
        } while (System.nanoTime() < deadline);
        return false;
    }

    private static void awaitLatch(CountDownLatch latch) {
        try {
            if (!latch.await(30, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Timed out waiting to release admission counter lock");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while holding admission counter lock", exception);
        }
    }

    private record DueTicket(String skuId, QueueTicketResponse ticket) {}
}
