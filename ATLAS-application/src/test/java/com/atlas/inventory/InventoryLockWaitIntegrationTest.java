package com.atlas.inventory;

import static org.assertj.core.api.Assertions.assertThat;

import com.atlas.catalog.dto.request.CreateProductRequest;
import com.atlas.catalog.dto.request.RegisterSellerSkuRequest;
import com.atlas.catalog.service.ProductService;
import com.atlas.catalog.service.SellerService;
import com.atlas.inventory.dto.request.ReserveInventoryBatchRequest;
import com.atlas.inventory.dto.response.InventoryPoolResponse;
import com.atlas.inventory.dto.response.ReservationResponse;
import com.atlas.inventory.persistence.state.ReservationStatus;
import com.atlas.inventory.service.InventoryService;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@Testcontainers
@SpringBootTest
class InventoryLockWaitIntegrationTest {
    private static final Duration WAIT_DEADLINE = Duration.ofSeconds(15);
    private static final Duration CLEANUP_DEADLINE = Duration.ofSeconds(5);
    private static final long POLL_INTERVAL_NANOS = TimeUnit.MILLISECONDS.toNanos(100);
    private static final String SELECTOR_QUERY = "SELECT pool.location_id, pool.sku_id ";

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

    @Autowired InventoryService inventory;
    @Autowired SellerService sellers;
    @Autowired ProductService products;
    @Autowired DataSource dataSource;
    @Autowired JdbcTemplate jdbc;

    @Test
    void observesSelectorWaiterThenReservesBatchAtomically() throws Exception {
        UUID sellerA = sellers.createSeller().sellerId();
        UUID sellerB = sellers.createSeller().sellerId();
        UUID firstSeller = Comparator.<UUID>naturalOrder().compare(sellerA, sellerB) < 0 ? sellerA : sellerB;
        UUID blockedSeller = firstSeller.equals(sellerA) ? sellerB : sellerA;
        String skuA = "lock-wait-" + UUID.randomUUID();
        String skuB = "lock-wait-" + UUID.randomUUID();
        registerSku(sellerA, skuA);
        registerSku(sellerB, skuB);
        UUID campaignId = UUID.randomUUID();
        UUID orderId = UUID.randomUUID();
        List<ReserveInventoryBatchRequest.Item> items = List.of(
                new ReserveInventoryBatchRequest.Item(sellerA, skuA, 1),
                new ReserveInventoryBatchRequest.Item(sellerB, skuB, 1));
        String blockedSku = blockedSeller.equals(sellerA) ? skuA : skuB;
        UUID blockedLocation = jdbc.queryForObject(
                "SELECT location_id FROM inventory_pool WHERE seller_id = ? AND sku_id = ?",
                UUID.class, blockedSeller, blockedSku);

        ExecutorService workerExecutor = Executors.newSingleThreadExecutor();
        Connection blocker = null;
        Future<List<ReservationResponse>> worker = null;
        int blockerPid = -1;
        try {
            blocker = dataSource.getConnection();
            blocker.setAutoCommit(false);
            blockerPid = backendPid(blocker);
            try (PreparedStatement lock = blocker.prepareStatement(
                    "SELECT 1 FROM inventory_pool WHERE seller_id = ? AND location_id = ? AND sku_id = ? FOR UPDATE")) {
                lock.setObject(1, blockedSeller);
                lock.setObject(2, blockedLocation);
                lock.setString(3, blockedSku);
                try (ResultSet rows = lock.executeQuery()) {
                    assertThat(rows.next()).isTrue();
                }
            }

            worker = workerExecutor.submit(() -> inventory.reserveAll(
                    new ReserveInventoryBatchRequest(orderId, campaignId, items)));
            try (Connection observer = dataSource.getConnection()) {
                int observerPid = backendPid(observer);
                int workerPid = awaitSelectorWaiter(observer, blockerPid, observerPid);
                assertThat(workerPid).isNotEqualTo(blockerPid).isNotEqualTo(observerPid);
                assertThat(inventory.findReservations(orderId)).isEmpty();
                for (var item : items) {
                    InventoryPoolResponse pool = inventory.find(item.sellerId(), item.skuId());
                    assertThat(pool.available()).isEqualTo(1);
                    assertThat(pool.reserved()).isZero();
                    assertThat(pool.claimed()).isZero();
                }
            }

            blocker.commit();
            blocker.close();
            blocker = null;
            List<ReservationResponse> reservations = worker.get(WAIT_DEADLINE.toMillis(), TimeUnit.MILLISECONDS);
            assertThat(reservations).hasSize(2).allSatisfy(reservation ->
                    assertThat(reservation.status()).isEqualTo(ReservationStatus.RESERVED));
            assertThat(inventory.findReservations(orderId)).hasSize(2);
            for (var item : items) {
                InventoryPoolResponse pool = inventory.find(item.sellerId(), item.skuId());
                assertThat(pool.initialCapacity()).isEqualTo(1);
                assertThat(pool.available()).isZero();
                assertThat(pool.reserved()).isEqualTo(1);
                assertThat(pool.claimed()).isZero();
                assertThat(pool.available() + pool.reserved() + pool.claimed()).isEqualTo(pool.initialCapacity());
            }
        } finally {
            if (blocker != null) {
                try {
                    blocker.rollback();
                } finally {
                    blocker.close();
                }
            }
            if (worker != null && !worker.isDone()) {
                worker.cancel(true);
            }
            workerExecutor.shutdownNow();
            workerExecutor.awaitTermination(CLEANUP_DEADLINE.toMillis(), TimeUnit.MILLISECONDS);
            jdbc.update("DELETE FROM inventory_reservation WHERE order_id = ?", orderId);
            jdbc.update("DELETE FROM inventory_pool WHERE seller_id = ? AND sku_id = ?", sellerA, skuA);
            jdbc.update("DELETE FROM inventory_pool WHERE seller_id = ? AND sku_id = ?", sellerB, skuB);
        }
    }

    private int awaitSelectorWaiter(Connection observer, int blockerPid, int observerPid) throws Exception {
        long deadline = System.nanoTime() + WAIT_DEADLINE.toNanos();
        String lastObservation = "no matching backend observed";
        while (System.nanoTime() < deadline) {
            try (PreparedStatement query = observer.prepareStatement("""
                    SELECT pid, wait_event_type, query, pg_blocking_pids(pid)
                    FROM pg_stat_activity
                    WHERE pid <> ? AND wait_event_type = 'Lock'
                      AND query LIKE ?
                      AND ? = ANY(pg_blocking_pids(pid))
                    """)) {
                query.setInt(1, observerPid);
                query.setString(2, SELECTOR_QUERY + "%FOR UPDATE OF pool%");
                query.setInt(3, blockerPid);
                try (ResultSet rows = query.executeQuery()) {
                    if (rows.next()) {
                        int pid = rows.getInt("pid");
                        assertThat(rows.next()).as("exactly one matching selector waiter").isFalse();
                        return pid;
                    }
                }
            }
            lastObservation = "no selector Lock waiter blocked by backend " + blockerPid;
            long remaining = deadline - System.nanoTime();
            if (remaining > 0) {
                TimeUnit.NANOSECONDS.sleep(Math.min(POLL_INTERVAL_NANOS, remaining));
            }
        }
        throw new AssertionError("Timed out waiting for PostgreSQL selector waiter: " + lastObservation);
    }

    private static int backendPid(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement(); ResultSet rows = statement.executeQuery("SELECT pg_backend_pid()")) {
            rows.next();
            return rows.getInt(1);
        }
    }

    private void registerSku(UUID sellerId, String sku) {
        var product = products.createProduct(sellerId,
                new CreateProductRequest("lock-wait-" + UUID.randomUUID(), null));
        products.registerSku(sellerId, product.productId(), new RegisterSellerSkuRequest(sku, 1, 1));
    }
}
