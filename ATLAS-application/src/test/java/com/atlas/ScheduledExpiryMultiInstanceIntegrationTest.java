package com.atlas;

import static org.assertj.core.api.Assertions.assertThat;

import com.atlas.admission.dto.request.ConfigureSellerSkuPolicyRequest;
import com.atlas.admission.dto.request.JoinQueueRequest;
import com.atlas.admission.dto.response.QueueTicketResponse;
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
import com.zaxxer.hikari.HikariDataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestReporter;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@Testcontainers
@SpringBootTest
@AutoConfigureMockMvc(addFilters = false)
class ScheduledExpiryMultiInstanceIntegrationTest extends PayPalIntegrationTestSupport {
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
        properties.add("spring.datasource.hikari.maximum-pool-size", () -> "12");
        properties.add("atlas.admission.expiry-poll-interval", () -> "PT24H");
        properties.add("atlas.orders.expiry-poll-interval", () -> "PT24H");
    }

    @Autowired @Qualifier("taskScheduler") private ThreadPoolTaskScheduler taskScheduler;
    @Autowired private SellerService sellers;
    @Autowired private ProductService products;
    @Autowired private CampaignService campaigns;
    @Autowired private AdmissionService admission;
    @Autowired private AdmissionServiceImpl admissionService;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private DataSource dataSource;

    @Test
    void twoApplicationContextsExpireSellerSkuTicketsExactlyOnce(TestReporter reporter) throws Exception {
        stopScheduler(taskScheduler);
        try (ConfigurableApplicationContext secondContext = startSecondInstance()) {
            ThreadPoolTaskScheduler secondScheduler = secondContext.getBean(
                    "taskScheduler", ThreadPoolTaskScheduler.class);
            stopScheduler(secondScheduler);
            DataSource secondDataSource = secondContext.getBean(DataSource.class);
            assertThat(secondDataSource).isNotSameAs(dataSource);
            assertThat(secondDataSource.unwrap(HikariDataSource.class))
                    .isNotSameAs(dataSource.unwrap(HikariDataSource.class));
            reporter.publishEntry("expiry.multiContext.instances", "2");
            reporter.publishEntry("expiry.multiContext.hikariMaximumPoolSize", "12");

            AdmissionServiceImpl secondAdmission = secondContext.getBean(AdmissionServiceImpl.class);
            for (int dueTicketCount : List.of(10, 100, 300)) {
                DueTicketProfile profile = createDueTicketProfile(dueTicketCount);
                runConcurrentExpiry(profile, secondAdmission, reporter);
            }
        }
    }

    private ConfigurableApplicationContext startSecondInstance() {
        return new SpringApplicationBuilder(AtlasApplication.class)
                .web(WebApplicationType.SERVLET)
                .run(
                        "--spring.datasource.url=" + POSTGRES.getJdbcUrl(),
                        "--spring.datasource.username=" + POSTGRES.getUsername(),
                        "--spring.datasource.password=" + POSTGRES.getPassword(),
                        "--spring.datasource.hikari.maximum-pool-size=12",
                        "--spring.flyway.enabled=false",
                        "--atlas.admission.expiry-poll-interval=PT24H",
                        "--atlas.orders.expiry-poll-interval=PT24H",
                        "--atlas.payment.provider=disabled",
                        "--spring.main.banner-mode=off",
                        "--server.address=127.0.0.1",
                        "--server.port=0");
    }

    private static void stopScheduler(ThreadPoolTaskScheduler scheduler) throws InterruptedException {
        scheduler.shutdown();
        assertThat(scheduler.getScheduledThreadPoolExecutor().awaitTermination(10, TimeUnit.SECONDS)).isTrue();
    }

    private DueTicketProfile createDueTicketProfile(int dueTicketCount) {
        UUID sellerId = sellers.createSeller().sellerId();
        String skuId = "scheduler-expiry-" + dueTicketCount + "-" + UUID.randomUUID();
        ProductResponse product = products.createProduct(sellerId,
                new CreateProductRequest("Expiry profile " + skuId, "scheduler expiry fixture"));
        int initialStock = dueTicketCount + 3;
        products.registerSku(sellerId, product.productId(),
                new RegisterSellerSkuRequest(skuId, 100, initialStock));
        admission.configureSellerSkuPolicy(new ConfigureSellerSkuPolicyRequest(sellerId, skuId, 1));
        CampaignResponse campaign = campaigns.create(
                new CreateCampaignRequest(sellerId, skuId, Duration.ofMinutes(5)));
        campaigns.open(campaign.campaignId());

        List<QueueTicketResponse> tickets = new ArrayList<>(dueTicketCount);
        for (int index = 0; index < dueTicketCount; index++) {
            tickets.add(admission.join(new JoinQueueRequest(
                    campaign.campaignId(), skuId,
                    "scheduler-expiry-buyer-" + UUID.randomUUID(),
                    "scheduler-expiry-key-" + UUID.randomUUID())));
        }
        jdbc.update("UPDATE admission_ticket SET expires_at = ? WHERE campaign_id = ?",
                Timestamp.from(Instant.now().minusSeconds(1)), campaign.campaignId());
        assertThat(tickets).hasSize(dueTicketCount);
        return new DueTicketProfile(sellerId, skuId, campaign.campaignId(), tickets);
    }

    private void runConcurrentExpiry(
            DueTicketProfile profile,
            AdmissionServiceImpl secondAdmission,
            TestReporter reporter) throws Exception {
        ExecutorService workers = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        try (Connection blocker = dataSource.getConnection()) {
            blocker.setAutoCommit(false);
            int blockerPid = PostgresLockWaitProbe.backendPid(blocker);
            try (PreparedStatement lock = blocker.prepareStatement(
                    "SELECT last_sequence FROM admission_counter WHERE seller_id = ? AND sku_id = ? FOR UPDATE")) {
                lock.setObject(1, profile.sellerId());
                lock.setString(2, profile.skuId());
                try (ResultSet rows = lock.executeQuery()) {
                    assertThat(rows.next()).isTrue();
                }
            }

            Future<Integer> firstExpiry = workers.submit(() -> {
                awaitStart(start);
                return admissionService.expireDueTickets();
            });
            Future<Integer> secondExpiry = workers.submit(() -> {
                awaitStart(start);
                return secondAdmission.expireDueTickets();
            });
            start.countDown();

            PostgresLockWaitProbe.Observation waiter;
            try (Connection observer = dataSource.getConnection()) {
                int observerPid = PostgresLockWaitProbe.backendPid(observer);
                waiter = PostgresLockWaitProbe.awaitWaiter(
                        observer, blockerPid, observerPid,
                        Duration.ofSeconds(15), Duration.ofMillis(100));
            }
            assertThat(waiter.waitEvent()).isNotBlank();
            assertThat(firstExpiry.isDone()).isFalse();
            assertThat(secondExpiry.isDone()).isFalse();

            blocker.commit();
            int firstChanged = firstExpiry.get(30, TimeUnit.SECONDS);
            int secondChanged = secondExpiry.get(30, TimeUnit.SECONDS);
            assertThat(firstChanged + secondChanged).isEqualTo(profile.tickets().size());
            assertThat(jdbc.queryForObject(
                    "SELECT COUNT(*) FROM admission_ticket WHERE campaign_id = ? AND status = 'EXPIRED'",
                    Integer.class, profile.campaignId())).isEqualTo(profile.tickets().size());
            assertThat(jdbc.queryForObject(
                    "SELECT COUNT(*) FROM admission_ticket WHERE campaign_id = ? AND checkout_slot_held",
                    Integer.class, profile.campaignId())).isZero();
            assertThat(jdbc.queryForObject(
                    "SELECT last_sequence FROM admission_counter WHERE seller_id = ? AND sku_id = ?",
                    Long.class, profile.sellerId(), profile.skuId())).isEqualTo((long) profile.tickets().size());

            String prefix = "admission.multiContext.rows." + profile.tickets().size();
            reporter.publishEntry(prefix + ".changedRows", Integer.toString(firstChanged + secondChanged));
            reporter.publishEntry(prefix + ".directWaiterCount", "1");
            reporter.publishEntry(prefix + ".invocationsPendingAtObservation", "2");
            reporter.publishEntry(prefix + ".waitEvent", waiter.waitEvent());
            reporter.publishEntry(prefix + ".queryElapsedAtObservationMs",
                    Double.toString(waiter.queryElapsedMillis()));
            reporter.publishEntry(prefix + ".pollIntervalMs", "100");
        } finally {
            start.countDown();
            workers.shutdownNow();
            workers.awaitTermination(10, TimeUnit.SECONDS);
        }
    }

    private static void awaitStart(CountDownLatch start) throws InterruptedException {
        if (!start.await(10, TimeUnit.SECONDS)) {
            throw new IllegalStateException("Timed out waiting for simultaneous expiry start");
        }
    }

    private record DueTicketProfile(UUID sellerId, String skuId, UUID campaignId,
                                    List<QueueTicketResponse> tickets) {
    }
}
