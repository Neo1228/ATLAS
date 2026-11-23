package com.atlas.admission;

import static org.assertj.core.api.Assertions.assertThat;

import com.atlas.PostgresLockWaitProbe;
import com.atlas.admission.dto.request.ConfigureSellerSkuPolicyRequest;
import com.atlas.admission.dto.request.JoinQueueRequest;
import com.atlas.admission.dto.response.QueueTicketResponse;
import com.atlas.admission.persistence.projection.DueTicketCandidate;
import com.atlas.admission.persistence.state.TicketStatus;
import com.atlas.admission.repository.QueueRepository;
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
import java.util.ArrayList;
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
    @Autowired private QueueRepository queueRepository;
    @Autowired private TicketRepository tickets;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private DataSource dataSource;
    @Autowired private PlatformTransactionManager transactionManager;

    @Test
    void expiresUnlockedSellerSkuAndSkipsAnotherInstanceHoldingTheNextCounter() throws Exception {
        SellerResponse seller = sellers.createSeller();
        ProductResponse product = products.createProduct(seller.sellerId(),
                new CreateProductRequest("admission expiry " + UUID.randomUUID(), "description"));
        DueTicket first = createDueTicket(seller.sellerId(), product, "first");
        DueTicket second = createDueTicket(seller.sellerId(), product, "second");

        List<DueTicketCandidate> candidates = queueRepository.findDueTicketCandidates(
                Instant.now(), null, 10, Duration.ofSeconds(2));
        assertThat(candidates).extracting(DueTicketCandidate::skuId)
                .containsExactlyInAnyOrder(first.skuId(), second.skuId());
        DueTicket visibleBeforeRelease = Map.of(first.skuId(), first, second.skuId(), second)
                .get(candidates.get(0).skuId());
        DueTicket blocked = Map.of(first.skuId(), first, second.skuId(), second)
                .get(candidates.get(1).skuId());

        CountDownLatch counterLocked = new CountDownLatch(1);
        CountDownLatch releaseCounter = new CountDownLatch(1);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        Future<?> lockHolder = executor.submit(() -> new TransactionTemplate(transactionManager).execute(status -> {
            if (!queueRepository.lockCounter(seller.sellerId(), blocked.skuId())) {
                throw new IllegalStateException("Admission counter is missing");
            }
            counterLocked.countDown();
            try {
                if (!releaseCounter.await(10, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("Timed out waiting to release admission counter");
                }
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Interrupted while holding admission counter", exception);
            }
            return null;
        }));

        try {
            assertThat(counterLocked.await(5, TimeUnit.SECONDS)).isTrue();

            int firstTickExpired = admissionService.expireDueTickets();

            assertThat(firstTickExpired).isEqualTo(1);
            assertThat(admission.find(visibleBeforeRelease.ticket().campaignId(),
                    visibleBeforeRelease.ticket().ticketId()).status()).isEqualTo(TicketStatus.EXPIRED);
            assertThat(admission.find(blocked.ticket().campaignId(), blocked.ticket().ticketId()).status())
                    .isEqualTo(TicketStatus.CHECKOUT_ACTIVE);

            releaseCounter.countDown();
            lockHolder.get(10, TimeUnit.SECONDS);
            assertThat(admissionService.expireDueTickets()).isEqualTo(1);
            assertThat(admission.find(blocked.ticket().campaignId(), blocked.ticket().ticketId()).status())
                    .isEqualTo(TicketStatus.EXPIRED);
        } finally {
            releaseCounter.countDown();
            executor.shutdownNow();
            executor.awaitTermination(10, TimeUnit.SECONDS);
        }
    }

    @Test
    void increasingPolicyCapacityImmediatelyPromotesNextWaitingTicketInFifoOrder() {
        SellerResponse seller = sellers.createSeller();
        ProductResponse product = products.createProduct(seller.sellerId(),
                new CreateProductRequest("admission capacity " + UUID.randomUUID(), "description"));
        String skuId = "admission-capacity-" + UUID.randomUUID();
        products.registerSku(seller.sellerId(), product.productId(), new RegisterSellerSkuRequest(skuId, 100, 5));
        admission.configureSellerSkuPolicy(new ConfigureSellerSkuPolicyRequest(seller.sellerId(), skuId, 1));
        CampaignResponse campaign = campaigns.create(new CreateCampaignRequest(
                seller.sellerId(), skuId, Duration.ofMinutes(5)));
        campaigns.open(campaign.campaignId());

        QueueTicketResponse first = admission.join(new JoinQueueRequest(
                campaign.campaignId(), skuId, "buyer-1", "capacity-1"));
        QueueTicketResponse second = admission.join(new JoinQueueRequest(
                campaign.campaignId(), skuId, "buyer-2", "capacity-2"));
        QueueTicketResponse third = admission.join(new JoinQueueRequest(
                campaign.campaignId(), skuId, "buyer-3", "capacity-3"));

        assertThat(first.sequence()).isEqualTo(1);
        assertThat(second.sequence()).isEqualTo(2);
        assertThat(third.sequence()).isEqualTo(3);
        assertThat(first.status()).isEqualTo(TicketStatus.CHECKOUT_ACTIVE);
        assertThat(second.status()).isEqualTo(TicketStatus.WAITING);
        assertThat(third.status()).isEqualTo(TicketStatus.WAITING);

        admission.configureSellerSkuPolicy(new ConfigureSellerSkuPolicyRequest(seller.sellerId(), skuId, 2));

        assertThat(admission.find(campaign.campaignId(), first.ticketId()).status())
                .isEqualTo(TicketStatus.CHECKOUT_ACTIVE);
        assertThat(admission.find(campaign.campaignId(), second.ticketId()).status())
                .isEqualTo(TicketStatus.CHECKOUT_ACTIVE);
        assertThat(admission.find(campaign.campaignId(), third.ticketId()).status())
                .isEqualTo(TicketStatus.WAITING);
    }


    @Test
    void joinContendingWithCapacityDecreaseUsesCommittedPolicy() throws Exception {
        SellerResponse seller = sellers.createSeller();
        ProductResponse product = products.createProduct(seller.sellerId(),
                new CreateProductRequest("admission policy race " + UUID.randomUUID(), "description"));
        String skuId = "admission-policy-race-" + UUID.randomUUID();
        products.registerSku(seller.sellerId(), product.productId(), new RegisterSellerSkuRequest(skuId, 100, 5));
        admission.configureSellerSkuPolicy(new ConfigureSellerSkuPolicyRequest(seller.sellerId(), skuId, 2));
        CampaignResponse campaign = campaigns.create(new CreateCampaignRequest(
                seller.sellerId(), skuId, Duration.ofMinutes(5)));
        campaigns.open(campaign.campaignId());
        QueueTicketResponse first = admission.join(new JoinQueueRequest(
                campaign.campaignId(), skuId, "buyer-policy-race-1", "policy-race-1"));
        assertThat(first.status()).isEqualTo(TicketStatus.CHECKOUT_ACTIVE);

        CountDownLatch configurationLocked = new CountDownLatch(1);
        CountDownLatch releaseConfiguration = new CountDownLatch(1);
        AtomicInteger blockerPid = new AtomicInteger(-1);
        ExecutorService executor = Executors.newFixedThreadPool(2, runnable -> {
            Thread thread = new Thread(runnable, "admission-policy-race");
            thread.setDaemon(true);
            return thread;
        });
        try {
            Future<?> configuration = executor.submit(() -> new TransactionTemplate(transactionManager)
                    .executeWithoutResult(status -> {
                        admission.configureSellerSkuPolicy(
                                new ConfigureSellerSkuPolicyRequest(seller.sellerId(), skuId, 1));
                        blockerPid.set(jdbc.queryForObject("SELECT pg_backend_pid()", Integer.class));
                        configurationLocked.countDown();
                        awaitLatch(releaseConfiguration);
                    }));
            assertThat(configurationLocked.await(5, TimeUnit.SECONDS)).isTrue();

            Future<QueueTicketResponse> join = executor.submit(() -> admission.join(new JoinQueueRequest(
                    campaign.campaignId(), skuId, "buyer-policy-race-2", "policy-race-2")));
            try (Connection observer = dataSource.getConnection()) {
                int observerPid = PostgresLockWaitProbe.backendPid(observer);
                PostgresLockWaitProbe.Observation observation = PostgresLockWaitProbe.awaitWaiter(
                        observer, blockerPid.get(), observerPid, Duration.ofSeconds(10), Duration.ofMillis(50));
                assertThat(observation.queryElapsedMillis()).isPositive();
            }

            releaseConfiguration.countDown();
            configuration.get(10, TimeUnit.SECONDS);
            QueueTicketResponse second = join.get(10, TimeUnit.SECONDS);

            assertThat(admission.find(campaign.campaignId(), first.ticketId()).status())
                    .isEqualTo(TicketStatus.CHECKOUT_ACTIVE);
            assertThat(second.status()).isEqualTo(TicketStatus.WAITING);
            assertThat(admission.find(campaign.campaignId(), second.ticketId()).status())
                    .isEqualTo(TicketStatus.WAITING);
        } finally {
            releaseConfiguration.countDown();
            executor.shutdownNow();
            executor.awaitTermination(5, TimeUnit.SECONDS);
        }
    }


    @Test
    void capacityIncreaseExpiresHeldTicketBeforePromotingOldestEligibleWaiters() {
        SellerResponse seller = sellers.createSeller();
        ProductResponse product = products.createProduct(seller.sellerId(),
                new CreateProductRequest("admission expired capacity " + UUID.randomUUID(), "description"));
        String skuId = "admission-expired-capacity-" + UUID.randomUUID();
        products.registerSku(seller.sellerId(), product.productId(), new RegisterSellerSkuRequest(skuId, 100, 5));
        admission.configureSellerSkuPolicy(new ConfigureSellerSkuPolicyRequest(seller.sellerId(), skuId, 1));
        CampaignResponse campaign = campaigns.create(new CreateCampaignRequest(
                seller.sellerId(), skuId, Duration.ofMinutes(5)));
        campaigns.open(campaign.campaignId());

        QueueTicketResponse expiredActive = admission.join(new JoinQueueRequest(
                campaign.campaignId(), skuId, "buyer-expired-active", "expired-active"));
        QueueTicketResponse oldestWaiting = admission.join(new JoinQueueRequest(
                campaign.campaignId(), skuId, "buyer-oldest-waiting", "oldest-waiting"));
        QueueTicketResponse nextWaiting = admission.join(new JoinQueueRequest(
                campaign.campaignId(), skuId, "buyer-next-waiting", "next-waiting"));
        QueueTicketResponse lastWaiting = admission.join(new JoinQueueRequest(
                campaign.campaignId(), skuId, "buyer-last-waiting", "last-waiting"));
        jdbc.update("UPDATE admission_ticket SET expires_at = ? WHERE ticket_id = ?",
                Timestamp.from(Instant.now().minus(Duration.ofHours(1))), expiredActive.ticketId());

        admission.configureSellerSkuPolicy(new ConfigureSellerSkuPolicyRequest(seller.sellerId(), skuId, 2));

        assertThat(admission.find(campaign.campaignId(), expiredActive.ticketId()).status())
                .isEqualTo(TicketStatus.EXPIRED);
        assertThat(admission.find(campaign.campaignId(), oldestWaiting.ticketId()).status())
                .isEqualTo(TicketStatus.CHECKOUT_ACTIVE);
        assertThat(admission.find(campaign.campaignId(), nextWaiting.ticketId()).status())
                .isEqualTo(TicketStatus.CHECKOUT_ACTIVE);
        assertThat(admission.find(campaign.campaignId(), lastWaiting.ticketId()).status())
                .isEqualTo(TicketStatus.WAITING);
    }


    @Test
    void scheduledExpiryDoesNotExpireTheTwoHundredFirstCandidateForOneSellerSku() {
        SellerResponse seller = sellers.createSeller();
        String skuId = "admission-expiry-page-" + UUID.randomUUID();
        ProductResponse product = products.createProduct(seller.sellerId(),
                new CreateProductRequest("admission expiry page " + UUID.randomUUID(), "description"));
        products.registerSku(seller.sellerId(), product.productId(), new RegisterSellerSkuRequest(skuId, 100, 5));
        admission.configureSellerSkuPolicy(new ConfigureSellerSkuPolicyRequest(seller.sellerId(), skuId, 1));
        CampaignResponse campaign = campaigns.create(new CreateCampaignRequest(
                seller.sellerId(), skuId, Duration.ofMinutes(5)));
        campaigns.open(campaign.campaignId());

        List<QueueTicketResponse> tickets = new ArrayList<>();
        for (int i = 0; i < 201; i++) {
            tickets.add(admission.join(new JoinQueueRequest(campaign.campaignId(), skuId,
                    "buyer-page-" + i + "-" + UUID.randomUUID(), "expiry-page-" + i + "-" + UUID.randomUUID())));
        }
        Instant dueAt = Instant.now().minus(Duration.ofHours(1));
        jdbc.update("UPDATE admission_ticket SET expires_at = ? WHERE seller_id = ? AND sku_id = ?",
                Timestamp.from(dueAt), seller.sellerId(), skuId);

        List<DueTicketCandidate> page = queueRepository.findDueTicketCandidates(
                Instant.now(), null, 201, Duration.ofSeconds(2));
        assertThat(page).hasSize(201);
        UUID ticketIdOutsidePage = page.get(200).ticketId();

        assertThat(admissionService.expireDueTickets()).isEqualTo(200);
        assertThat(jdbc.queryForObject("SELECT status FROM admission_ticket WHERE ticket_id = ?",
                String.class, ticketIdOutsidePage)).isNotEqualTo("EXPIRED");
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
