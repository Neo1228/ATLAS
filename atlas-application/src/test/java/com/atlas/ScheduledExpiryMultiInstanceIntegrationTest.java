package com.atlas;

import static org.assertj.core.api.Assertions.assertThat;

import com.atlas.admission.dto.request.ConfigureSellerSkuPolicyRequest;
import com.atlas.admission.dto.request.JoinQueueRequest;
import com.atlas.admission.dto.response.QueueTicketResponse;
import com.atlas.admission.service.AdmissionService;
import com.atlas.admission.service.impl.AdmissionServiceImpl;
import com.atlas.admission.dto.request.CreateCampaignRequest;
import com.atlas.admission.dto.response.CampaignResponse;
import com.atlas.admission.service.CampaignService;
import com.atlas.catalog.dto.request.CreateProductRequest;
import com.atlas.catalog.dto.request.RegisterSellerSkuRequest;
import com.atlas.catalog.dto.response.ProductResponse;
import com.atlas.catalog.dto.response.SellerResponse;
import com.atlas.catalog.service.ProductService;
import com.atlas.catalog.service.SellerService;
import com.atlas.inventory.dto.request.ReserveInventoryRequest;
import com.atlas.inventory.model.ReservationStatus;
import com.atlas.inventory.service.InventoryService;
import com.atlas.order.service.CheckoutPaymentService;
import com.atlas.payment.PayPalIntegrationTestSupport;
import com.zaxxer.hikari.HikariDataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
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
    private static final String PRIMARY_DATABASE_APPLICATION_NAME = "atlas-expiry-primary";
    private static final String SECONDARY_DATABASE_APPLICATION_NAME = "atlas-expiry-secondary";
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
        properties.add("spring.datasource.hikari.connection-init-sql",
                () -> "SET application_name = '" + PRIMARY_DATABASE_APPLICATION_NAME + "'");
        properties.add("atlas.admission.expiry-poll-interval", () -> "PT24H");
        properties.add("atlas.admission.expiry-sweep-batch-size", () -> "10");
        properties.add("atlas.admission.expiry-sweep-time-budget", () -> "PT30S");
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
    @Autowired private CheckoutPaymentService checkoutPayments;
    @Autowired private InventoryService inventory;

    @Test
    void admissionExpiryDrainsOnlyTheConfiguredSellerSkuBatchPerTick() {
        List<DueTicketProfile> profiles = new ArrayList<>();
        for (int index = 0; index < 25; index++) {
            profiles.add(createDueTicketProfile(1));
        }

        assertThat(admissionService.expireDueTickets()).isEqualTo(10);
        assertThat(expiredProfileCount(profiles)).isEqualTo(10);
        assertThat(admissionService.expireDueTickets()).isEqualTo(10);
        assertThat(expiredProfileCount(profiles)).isEqualTo(20);
        assertThat(admissionService.expireDueTickets()).isEqualTo(5);
        assertThat(expiredProfileCount(profiles)).isEqualTo(25);
    }

    private int expiredProfileCount(List<DueTicketProfile> profiles) {
        return profiles.stream().mapToInt(profile -> jdbc.queryForObject(
                "SELECT COUNT(*) FROM admission_ticket WHERE campaign_id = ? AND status = 'EXPIRED'",
                Integer.class, profile.campaignId())).sum();
    }

    @Test
    void twoApplicationContextsSkipBusySellerSkuAndALaterTickExpiresIt(TestReporter reporter) throws Exception {
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

    @Test
    void checkoutExpiryIsExactlyOnceAcrossInstancesWhileAnotherDueCheckoutProgresses() throws Exception {
        stopScheduler(taskScheduler);
        try (ConfigurableApplicationContext secondContext = startSecondInstance()) {
            ThreadPoolTaskScheduler secondScheduler = secondContext.getBean(
                    "taskScheduler", ThreadPoolTaskScheduler.class);
            stopScheduler(secondScheduler);
            CheckoutPaymentService secondCheckouts = secondContext.getBean(CheckoutPaymentService.class);

            UUID sellerId = sellers.createSeller().sellerId();
            String skuId = "multi-instance-expiry-" + UUID.randomUUID();
            ProductResponse product = products.createProduct(sellerId,
                    new CreateProductRequest("Multi-instance expiry", "shared expiry fixture"));
            products.registerSku(sellerId, product.productId(),
                    new RegisterSellerSkuRequest(skuId, 100, 2));

            UUID earlierCheckoutId = UUID.fromString("00000000-0000-0000-0000-000000000001");
            UUID blockedCheckoutId = UUID.fromString("ffffffff-ffff-ffff-ffff-ffffffffffff");
            UUID earlierOrderId = UUID.randomUUID();
            UUID blockedOrderId = UUID.randomUUID();
            Instant expiresAt = Instant.now().minusSeconds(60);
            insertDueCartCheckout(earlierCheckoutId, earlierOrderId, sellerId, skuId,
                    "multi-expiry-earlier-" + UUID.randomUUID(), expiresAt);
            insertDueCartCheckout(blockedCheckoutId, blockedOrderId, sellerId, skuId,
                    "multi-expiry-blocked-" + UUID.randomUUID(), expiresAt);

            ExecutorService workers = Executors.newFixedThreadPool(2);
            CountDownLatch workersReady = new CountDownLatch(2);
            CountDownLatch start = new CountDownLatch(1);
            Connection blocker = dataSource.getConnection();
            try {
                blocker.setAutoCommit(false);
                try (PreparedStatement lock = blocker.prepareStatement(
                        "SELECT checkout_id FROM buyer_checkout WHERE checkout_id = ? FOR UPDATE")) {
                    lock.setObject(1, blockedCheckoutId);
                    try (ResultSet rows = lock.executeQuery()) {
                        assertThat(rows.next()).isTrue();
                    }
                }

                Future<Integer> primaryExpiry = workers.submit(() -> {
                    workersReady.countDown();
                    awaitStart(start);
                    return checkoutPayments.expireDueCheckouts();
                });
                Future<Integer> secondaryExpiry = workers.submit(() -> {
                    workersReady.countDown();
                    awaitStart(start);
                    return secondCheckouts.expireDueCheckouts();
                });
                assertThat(workersReady.await(10, TimeUnit.SECONDS)).isTrue();
                start.countDown();

                // 한 replica만 sweep을 수행해 blocked 행의 락을 기다리고, 다른 replica는 후보 조회 없이 건너뛴다.
                String waitingInstance = awaitSingleCheckoutExpiryLockWaiter();
                boolean primaryWaits = PRIMARY_DATABASE_APPLICATION_NAME.equals(waitingInstance);
                Future<Integer> sweeping = primaryWaits ? primaryExpiry : secondaryExpiry;
                Future<Integer> skipped = primaryWaits ? secondaryExpiry : primaryExpiry;
                assertThat(sweeping.isDone()).isFalse();
                assertThat(skipped.get(10, TimeUnit.SECONDS)).isZero();
                assertThat(checkoutState(earlierCheckoutId)).isEqualTo("EXPIRED");
                assertThat(orderState(earlierOrderId)).isEqualTo("EXPIRED");
                assertReservationStatus(earlierOrderId, ReservationStatus.RELEASED);
                assertThat(checkoutState(blockedCheckoutId)).isEqualTo("RESERVED");
                assertThat(orderState(blockedOrderId)).isEqualTo("RESERVED");
                assertReservationStatus(blockedOrderId, ReservationStatus.RESERVED);
                assertThat(inventory.find(sellerId, skuId).available()).isEqualTo(1);
                assertThat(inventory.find(sellerId, skuId).reserved()).isEqualTo(1);

                blocker.commit();
                int expiredByPrimary = primaryExpiry.get(10, TimeUnit.SECONDS);
                int expiredBySecondary = secondaryExpiry.get(10, TimeUnit.SECONDS);
                assertThat(expiredByPrimary + expiredBySecondary).isEqualTo(2);
            } finally {
                start.countDown();
                try {
                    blocker.rollback();
                } catch (SQLException cleanupException) {
                    // 테스트 본문의 assertion 실패를 보존한다.
                }
                try {
                    blocker.close();
                } catch (SQLException cleanupException) {
                    // 테스트 본문의 assertion 실패를 보존한다.
                }
                workers.shutdownNow();
                assertThat(workers.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
            }

            assertThat(checkoutState(earlierCheckoutId)).isEqualTo("EXPIRED");
            assertThat(orderState(earlierOrderId)).isEqualTo("EXPIRED");
            assertThat(checkoutState(blockedCheckoutId)).isEqualTo("EXPIRED");
            assertThat(orderState(blockedOrderId)).isEqualTo("EXPIRED");
            assertReservationStatus(earlierOrderId, ReservationStatus.RELEASED);
            assertReservationStatus(blockedOrderId, ReservationStatus.RELEASED);
            assertThat(inventory.find(sellerId, skuId).available()).isEqualTo(2);
            assertThat(inventory.find(sellerId, skuId).reserved()).isZero();
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
                        "--server.port=0",
                        "--spring.datasource.hikari.connection-init-sql=SET application_name = '"
                                + SECONDARY_DATABASE_APPLICATION_NAME + "'");
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

            // Neither scheduler waits on the counter held by another worker.
            assertThat(firstExpiry.get(10, TimeUnit.SECONDS)).isZero();
            assertThat(secondExpiry.get(10, TimeUnit.SECONDS)).isZero();
            blocker.commit();

            int drained = 0;
            int ticks = 0;
            while (drained < profile.tickets().size() && ticks < profile.tickets().size() + 2) {
                int tickExpired = admissionService.expireDueTickets();
                drained += tickExpired;
                ticks++;
            }
            assertThat(drained).isEqualTo(profile.tickets().size());
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
            reporter.publishEntry(prefix + ".busyWorkersSkipped", "2");
            reporter.publishEntry(prefix + ".laterTickExpiredRows", Integer.toString(drained));
        } finally {
            start.countDown();
            workers.shutdownNow();
            workers.awaitTermination(10, TimeUnit.SECONDS);
        }
    }

    private void insertDueCartCheckout(
            UUID checkoutId, UUID orderId, UUID sellerId, String skuId, String buyerRef, Instant expiresAt) {
        inventory.reserve(new ReserveInventoryRequest(orderId, null, sellerId, skuId, 1));
        jdbc.update("""
                INSERT INTO buyer_checkout (
                    checkout_id, buyer_ref, idempotency_key, state, total_won, created_at, updated_at
                ) VALUES (?, ?, ?, 'RESERVED', 0, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """, checkoutId, buyerRef, "multi-instance-checkout-" + checkoutId);
        jdbc.update("""
                INSERT INTO purchase_order (
                    order_id, seller_id, buyer_ref, checkout_key, checkout_id, state,
                    checkout_expires_at, created_at, updated_at, order_source
                ) VALUES (?, ?, ?, ?, ?, 'RESERVED', ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'CART')
                """, orderId, sellerId, buyerRef, "multi-instance-key-" + checkoutId, checkoutId,
                Timestamp.from(expiresAt));
    }

    private String checkoutState(UUID checkoutId) {
        return jdbc.queryForObject(
                "SELECT state FROM buyer_checkout WHERE checkout_id = ?", String.class, checkoutId);
    }

    private String orderState(UUID orderId) {
        return jdbc.queryForObject(
                "SELECT state FROM purchase_order WHERE order_id = ?", String.class, orderId);
    }

    private void assertReservationStatus(UUID orderId, ReservationStatus expected) {
        assertThat(inventory.findReservations(orderId))
                .singleElement()
                .satisfies(reservation -> assertThat(reservation.status()).isEqualTo(expected));
    }

    private String awaitSingleCheckoutExpiryLockWaiter() throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        List<String> waitingInstances = List.of();
        while (System.nanoTime() < deadline) {
            waitingInstances = jdbc.query("""
                    SELECT application_name
                    FROM pg_stat_activity
                    WHERE application_name IN (?, ?)
                      AND wait_event_type = 'Lock'
                      AND state = 'active'
                      AND pid <> pg_backend_pid()
                    """, (rows, row) -> rows.getString(1),
                    PRIMARY_DATABASE_APPLICATION_NAME, SECONDARY_DATABASE_APPLICATION_NAME);
            if (waitingInstances.size() == 1) {
                return waitingInstances.getFirst();
            }
            TimeUnit.MILLISECONDS.sleep(50);
        }
        throw new AssertionError("exactly one instance should wait for the row lock, but waiting=" + waitingInstances);
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
