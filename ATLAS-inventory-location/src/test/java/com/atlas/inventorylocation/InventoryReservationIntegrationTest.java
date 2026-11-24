package com.atlas.inventorylocation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;

import com.atlas.inventorylocation.dto.request.InventoryCancellationRequest;
import com.atlas.inventorylocation.dto.request.InventoryReservationRequest;
import com.atlas.inventorylocation.dto.request.InventoryReservationRequest.Item;
import com.atlas.inventorylocation.dto.request.LocationStockRequest;
import com.atlas.inventorylocation.dto.response.InventoryReservationResponse;
import com.atlas.inventorylocation.service.InventoryReservationService;
import com.atlas.inventorylocation.exception.InventoryLocationException;
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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@Testcontainers
@AutoConfigureMockMvc
@SpringBootTest
class InventoryReservationIntegrationTest {
    private static final UUID FULFILLMENT_LOCATION_ID =
            UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID SELLER_ID = UUID.fromString("00000000-0000-0000-0000-000000000010");
    private static final int LAST_UNIT_ATTEMPTS = 100;
    private static final String SERVICE_TOKEN = "integration-test-inventory-service-token-32bytes";

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18.6")
            .withDatabaseName("atlas_inventory")
            .withUsername("atlas_inventory")
            .withPassword("atlas_inventory_test");

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry properties) {
        properties.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        properties.add("spring.datasource.username", POSTGRES::getUsername);
        properties.add("spring.datasource.password", POSTGRES::getPassword);
        properties.add("inventory.fulfillment-location-id", FULFILLMENT_LOCATION_ID::toString);
        properties.add("inventory.service-token", () -> SERVICE_TOKEN);
        properties.add("inventory.expiry.worker-enabled", () -> "false");
    }

    @Autowired
    private InventoryReservationService inventory;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private MockMvc mockMvc;

    @BeforeEach
    void clearInventoryState() {
        jdbc.update("DELETE FROM inventory_reservation_restock");
        jdbc.update("DELETE FROM inventory_reservation_item");
        jdbc.update("DELETE FROM inventory_reservation");
        jdbc.update("DELETE FROM inventory_location_stock");
    }

    @Test
    void retryAfterClientDropsReservationResponseReturnsTheSameReservation() throws Exception {
        seedStock("sku-a", 2);
        String idempotencyKey = "lost-response-" + UUID.randomUUID();
        UUID orderId = UUID.randomUUID();
        String body = """
                {"orderId":"%s","items":[{"sellerId":"%s","skuId":"sku-a","quantity":1}]}
                """.formatted(orderId, SELLER_ID);

        // 첫 응답은 의도적으로 버린다. 호출자는 원래 idempotency key와 요청 내용만 알고 있다.
        mockMvc.perform(post(reservationPath())
                        .header("X-Atlas-Service-Token", SERVICE_TOKEN)
                        .header("Idempotency-Key", idempotencyKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andReturn();
        var retry = mockMvc.perform(post(reservationPath())
                        .header("X-Atlas-Service-Token", SERVICE_TOKEN)
                        .header("Idempotency-Key", idempotencyKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andReturn();

        assertThat(retry.getResponse().getStatus()).isEqualTo(200);
        UUID reservationId = jdbc.queryForObject("""
                SELECT reservation_id FROM inventory_reservation
                WHERE fulfillment_location_id = ? AND idempotency_key = ?
                """, UUID.class, FULFILLMENT_LOCATION_ID, idempotencyKey);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM inventory_reservation", Integer.class)).isEqualTo(1);
        assertStock("sku-a", 2, 1, 1);
        assertThat(retry.getResponse().getContentAsString()).contains(reservationId.toString(), "\"replayed\":true", "\"sellerId\":\"" + SELLER_ID + "\"");
    }

    @Test
    void changedFactsWithTheSameIdempotencyKeyConflictWithoutChangingStock() {
        seedStock("sku-a", 4);
        String key = "changed-facts-" + UUID.randomUUID();
        UUID orderId = UUID.randomUUID();
        inventory.reserve(FULFILLMENT_LOCATION_ID, key, request(orderId, new Item(SELLER_ID, "sku-a", 1)));

        assertThatThrownBy(() -> inventory.reserve(
                FULFILLMENT_LOCATION_ID, key, request(orderId, new Item(SELLER_ID, "sku-a", 2))))
                .isInstanceOf(InventoryLocationException.class)
                .satisfies(exception -> assertThat(((InventoryLocationException) exception).code())
                        .isEqualTo(InventoryLocationException.Code.IDEMPOTENCY_CONFLICT));
        assertStock("sku-a", 4, 3, 1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM inventory_reservation", Integer.class)).isEqualTo(1);
    }

    @Test
    void insufficientSecondLineRollsBackTheWholeReservation() {
        seedStock("sku-a", 3);
        seedStock("sku-b", 0);
        UUID orderId = UUID.randomUUID();

        assertThatThrownBy(() -> inventory.reserve(FULFILLMENT_LOCATION_ID, "atomic-" + UUID.randomUUID(),
                request(orderId, new Item(SELLER_ID, "sku-a", 2), new Item(SELLER_ID, "sku-b", 1))))
                .isInstanceOf(InventoryLocationException.class)
                .satisfies(exception -> assertThat(((InventoryLocationException) exception).code())
                        .isEqualTo(InventoryLocationException.Code.INSUFFICIENT_STOCK));

        assertStock("sku-a", 3, 3, 0);
        assertStock("sku-b", 0, 0, 0);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM inventory_reservation", Integer.class)).isZero();
    }

    @Test
    void oneHundredConcurrentRequestsForTheLastUnitNeverOversell() throws Exception {
        seedStock("hot-sku", 1);
        CountDownLatch ready = new CountDownLatch(LAST_UNIT_ATTEMPTS);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Boolean>> futures = new ArrayList<>(LAST_UNIT_ATTEMPTS);
        try (ExecutorService executor = Executors.newFixedThreadPool(LAST_UNIT_ATTEMPTS)) {
            for (int i = 0; i < LAST_UNIT_ATTEMPTS; i++) {
                int requestNumber = i;
                futures.add(executor.submit(() -> {
                    ready.countDown();
                    if (!start.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("Start gate timed out");
                    try {
                        inventory.reserve(FULFILLMENT_LOCATION_ID, "hot-key-" + requestNumber,
                                request(UUID.randomUUID(), new Item(SELLER_ID, "hot-sku", 1)));
                        return true;
                    } catch (InventoryLocationException exception) {
                        if (exception.code() != InventoryLocationException.Code.INSUFFICIENT_STOCK) throw exception;
                        return false;
                    }
                }));
            }
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            long successCount = 0;
            for (Future<Boolean> future : futures) {
                if (future.get(30, TimeUnit.SECONDS)) successCount++;
            }

            assertThat(successCount).isEqualTo(1);
            assertStock("hot-sku", 1, 0, 1);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM inventory_reservation", Integer.class)).isEqualTo(1);
        }
    }

    @Test
    void claimAndReleaseRaceHasOneWinnerAndPreservesStock() throws Exception {
        seedStock("race-sku", 1);
        InventoryReservationResponse reservation = inventory.reserve(FULFILLMENT_LOCATION_ID,
                "claim-release-" + UUID.randomUUID(), request(UUID.randomUUID(), new Item(SELLER_ID, "race-sku", 1)));
        CountDownLatch start = new CountDownLatch(1);
        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<String> claim = executor.submit(() -> runTransition(start,
                    () -> inventory.claim(FULFILLMENT_LOCATION_ID, reservation.reservationId())));
            Future<String> release = executor.submit(() -> runTransition(start,
                    () -> inventory.release(FULFILLMENT_LOCATION_ID, reservation.reservationId())));
            start.countDown();
            List<String> outcomes = List.of(claim.get(10, TimeUnit.SECONDS), release.get(10, TimeUnit.SECONDS));
            assertThat(outcomes).containsExactlyInAnyOrder("APPLIED", "CONFLICT");
        }

        String status = jdbc.queryForObject("""
                SELECT status FROM inventory_reservation WHERE reservation_id = ?
                """, String.class, reservation.reservationId());
        var stock = inventory.findStock(FULFILLMENT_LOCATION_ID, SELLER_ID, "race-sku");
        assertThat(stock.reserved()).isZero();
        assertThat(stock.available() + stock.claimed()).isEqualTo(1);
        if ("CLAIMED".equals(status)) {
            assertThat(stock.available()).isZero();
            assertThat(stock.claimed()).isEqualTo(1);
        } else {
            assertThat(status).isEqualTo("RELEASED");
            assertThat(stock.available()).isEqualTo(1);
            assertThat(stock.claimed()).isZero();
        }
    }

    @Test
    void expiryWorkerAndLateClaimRaceCannotBothReleaseAndClaim() throws Exception {
        seedStock("expiry-sku", 3);
        InventoryReservationResponse reservation = inventory.reserve(FULFILLMENT_LOCATION_ID,
                "expiry-race-" + UUID.randomUUID(), request(UUID.randomUUID(), new Item(SELLER_ID, "expiry-sku", 2)));
        jdbc.update("UPDATE inventory_reservation SET expires_at = ? WHERE reservation_id = ?",
                Timestamp.from(Instant.now().minus(Duration.ofSeconds(1))), reservation.reservationId());
        CountDownLatch start = new CountDownLatch(1);
        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<Integer> expiry = executor.submit(() -> {
                start.await();
                return inventory.expireDue(100);
            });
            Future<String> claim = executor.submit(() -> {
                start.await();
                try {
                    inventory.claim(FULFILLMENT_LOCATION_ID, reservation.reservationId());
                    return "CLAIMED";
                } catch (InventoryLocationException exception) {
                    return exception.code().name();
                }
            });
            start.countDown();
            int expiredCount = expiry.get(10, TimeUnit.SECONDS);
            assertThat(claim.get(10, TimeUnit.SECONDS))
                    .isIn(InventoryLocationException.Code.RESERVATION_EXPIRED.name(),
                            InventoryLocationException.Code.INVALID_STATE.name());
            for (int retry = 0; retry < 3; retry++) {
                String status = jdbc.queryForObject(
                        "SELECT status FROM inventory_reservation WHERE reservation_id = ?",
                        String.class, reservation.reservationId());
                if (!"RESERVED".equals(status)) break;
                expiredCount += inventory.expireDue(100);
            }
            assertThat(expiredCount).isEqualTo(1);
        }

        assertThat(jdbc.queryForObject("SELECT status FROM inventory_reservation WHERE reservation_id = ?",
                String.class, reservation.reservationId())).isEqualTo("EXPIRED");
        assertStock("expiry-sku", 3, 3, 0);
        assertThat(inventory.expireDue(100)).isZero();
    }

    @Test
    void internalInventoryApiRejectsRequestsWithoutServiceToken() throws Exception {
        mockMvc.perform(get("/internal/v1/fulfillment-locations/{locationId}/stock/{skuId}",
                        FULFILLMENT_LOCATION_ID, "sku-missing"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void compensationBeforeReservationTombstonesTheIdempotencyKey() {
        seedStock("cancel-first-sku", 1);
        UUID orderId = UUID.randomUUID();
        String key = "cancel-before-reserve-" + UUID.randomUUID();

        InventoryReservationResponse cancelled = inventory.cancel(FULFILLMENT_LOCATION_ID, key,
                new InventoryCancellationRequest(orderId));

        assertThat(cancelled.status()).isEqualTo("CANCELLED");
        assertThat(cancelled.items()).isEmpty();
        assertStock("cancel-first-sku", 1, 1, 0);
        assertThatThrownBy(() -> inventory.reserve(FULFILLMENT_LOCATION_ID, key,
                request(orderId, new Item(SELLER_ID, "cancel-first-sku", 1))))
                .isInstanceOf(InventoryLocationException.class)
                .satisfies(exception -> assertThat(((InventoryLocationException) exception).code())
                        .isEqualTo(InventoryLocationException.Code.RESERVATION_CANCELLED));

        InventoryReservationResponse replay = inventory.cancel(FULFILLMENT_LOCATION_ID, key,
                new InventoryCancellationRequest(orderId));
        assertThat(replay.reservationId()).isEqualTo(cancelled.reservationId());
        assertThat(replay.replayed()).isTrue();
        assertStock("cancel-first-sku", 1, 1, 0);
    }

    @Test
    void concurrentReserveAndCompensationCannotResurrectStock() throws Exception {
        seedStock("cancel-race-sku", 1);
        UUID orderId = UUID.randomUUID();
        String key = "cancel-reserve-race-" + UUID.randomUUID();
        CountDownLatch start = new CountDownLatch(1);
        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<String> reserve = executor.submit(() -> {
                if (!start.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("Start gate timed out");
                try {
                    inventory.reserve(FULFILLMENT_LOCATION_ID, key,
                            request(orderId, new Item(SELLER_ID, "cancel-race-sku", 1)));
                    return "RESERVED";
                } catch (InventoryLocationException exception) {
                    if (exception.code() != InventoryLocationException.Code.RESERVATION_CANCELLED) throw exception;
                    return "TOMBSTONED";
                }
            });
            Future<InventoryReservationResponse> cancel = executor.submit(() -> {
                if (!start.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("Start gate timed out");
                return inventory.cancel(FULFILLMENT_LOCATION_ID, key,
                        new InventoryCancellationRequest(orderId));
            });
            start.countDown();
            assertThat(reserve.get(10, TimeUnit.SECONDS)).isIn("RESERVED", "TOMBSTONED");
            assertThat(cancel.get(10, TimeUnit.SECONDS).status()).isEqualTo("CANCELLED");
        }

        assertThat(jdbc.queryForObject("""
                SELECT status FROM inventory_reservation
                WHERE fulfillment_location_id = ? AND idempotency_key = ?
                """, String.class, FULFILLMENT_LOCATION_ID, key)).isEqualTo("CANCELLED");
        assertStock("cancel-race-sku", 1, 1, 0);
    }

    @Test
    void sameSkuForDifferentSellersReservesAndTransitionsIndependently() {
        UUID secondSeller = UUID.fromString("00000000-0000-0000-0000-000000000011");
        seedStock(SELLER_ID, "shared-sku", 2);
        seedStock(secondSeller, "shared-sku", 3);
        UUID orderA = UUID.randomUUID();
        UUID orderB = UUID.randomUUID();
        InventoryReservationResponse a = inventory.reserve(FULFILLMENT_LOCATION_ID, "seller-a-" + UUID.randomUUID(),
                request(orderA, new Item(SELLER_ID, "shared-sku", 1)));
        InventoryReservationResponse replayA = inventory.reserve(FULFILLMENT_LOCATION_ID, a.idempotencyKey(),
                request(orderA, new Item(SELLER_ID, "shared-sku", 1)));
        InventoryReservationResponse b = inventory.reserve(FULFILLMENT_LOCATION_ID, "seller-b-" + UUID.randomUUID(),
                request(orderB, new Item(secondSeller, "shared-sku", 2)));
        assertThat(replayA.replayed()).isTrue();
        assertThat(a.items().getFirst().sellerId()).isEqualTo(SELLER_ID);
        assertThat(b.items().getFirst().sellerId()).isEqualTo(secondSeller);
        inventory.claim(FULFILLMENT_LOCATION_ID, a.reservationId());
        inventory.release(FULFILLMENT_LOCATION_ID, b.reservationId());
        assertThat(inventory.findStock(FULFILLMENT_LOCATION_ID, SELLER_ID, "shared-sku").claimed()).isEqualTo(1);
        assertThat(inventory.findStock(FULFILLMENT_LOCATION_ID, secondSeller, "shared-sku").available()).isEqualTo(3);

        UUID cancelOrderId = UUID.randomUUID();
        String cancelKey = "cancel-seller-b-" + UUID.randomUUID();
        InventoryReservationResponse cancel = inventory.reserve(FULFILLMENT_LOCATION_ID, cancelKey,
                request(cancelOrderId, new Item(secondSeller, "shared-sku", 1)));
        inventory.cancel(FULFILLMENT_LOCATION_ID, cancelKey, new InventoryCancellationRequest(cancelOrderId));
        assertThat(inventory.findReservation(FULFILLMENT_LOCATION_ID, cancel.reservationId()).status()).isEqualTo("CANCELLED");
        InventoryReservationResponse expiry = inventory.reserve(FULFILLMENT_LOCATION_ID, "expire-seller-a-" + UUID.randomUUID(),
                request(UUID.randomUUID(), new Item(SELLER_ID, "shared-sku", 1)));
        jdbc.update("UPDATE inventory_reservation SET expires_at = ? WHERE reservation_id = ?",
                Timestamp.from(Instant.now().minus(Duration.ofSeconds(1))), expiry.reservationId());
        assertThat(inventory.expireDue(100)).isEqualTo(1);
        assertThat(inventory.findStock(FULFILLMENT_LOCATION_ID, SELLER_ID, "shared-sku").available()).isEqualTo(1);
        assertThat(inventory.findStock(FULFILLMENT_LOCATION_ID, secondSeller, "shared-sku").available()).isEqualTo(3);
    }

    @Test
    void restoreReturnsOnlyClaimedStockAndIsIdempotent() {
        seedStock("restore-sku", 2);
        InventoryReservationResponse claimed = inventory.reserve(FULFILLMENT_LOCATION_ID, "restore-" + UUID.randomUUID(),
                request(UUID.randomUUID(), new Item(SELLER_ID, "restore-sku", 1)));
        InventoryReservationResponse reservedOnly = inventory.reserve(FULFILLMENT_LOCATION_ID,
                "restore-reserved-" + UUID.randomUUID(), request(UUID.randomUUID(), new Item(SELLER_ID, "restore-sku", 1)));
        inventory.claim(FULFILLMENT_LOCATION_ID, claimed.reservationId());

        InventoryReservationResponse restored = inventory.restore(FULFILLMENT_LOCATION_ID, claimed.reservationId());
        InventoryReservationResponse replay = inventory.restore(FULFILLMENT_LOCATION_ID, claimed.reservationId());

        assertThat(restored.status()).isEqualTo("RELEASED");
        assertThat(replay.replayed()).isTrue();
        assertStock("restore-sku", 2, 1, 1);
        // 판매 확정 전 예약은 restore가 아니라 cancel/release 대상이다.
        assertThatThrownBy(() -> inventory.restore(FULFILLMENT_LOCATION_ID, reservedOnly.reservationId()))
                .isInstanceOf(InventoryLocationException.class);
        assertStock("restore-sku", 2, 1, 1);
    }

    @Test
    void restockReturnsSoldUnitsOncePerKeyAndNeverExceedsTheSoldQuantity() {
        seedStock("restock-sku", 3);
        InventoryReservationResponse sold = inventory.reserve(FULFILLMENT_LOCATION_ID, "restock-" + UUID.randomUUID(),
                request(UUID.randomUUID(), new Item(SELLER_ID, "restock-sku", 2)));
        inventory.claim(FULFILLMENT_LOCATION_ID, sold.reservationId());
        String firstRefund = "refund-restock:" + UUID.randomUUID();

        InventoryReservationResponse first = inventory.restock(FULFILLMENT_LOCATION_ID, sold.reservationId(),
                firstRefund, List.of(new Item(SELLER_ID, "restock-sku", 1)));
        InventoryReservationResponse replay = inventory.restock(FULFILLMENT_LOCATION_ID, sold.reservationId(),
                firstRefund, List.of(new Item(SELLER_ID, "restock-sku", 1)));

        assertThat(first.replayed()).isFalse();
        assertThat(replay.replayed()).isTrue();
        var stock = inventory.findStock(FULFILLMENT_LOCATION_ID, SELLER_ID, "restock-sku");
        assertThat(stock.available()).isEqualTo(2);
        assertThat(stock.claimed()).isEqualTo(1);
        assertThatThrownBy(() -> inventory.restock(FULFILLMENT_LOCATION_ID, sold.reservationId(), firstRefund,
                List.of(new Item(SELLER_ID, "restock-sku", 2))))
                .isInstanceOfSatisfying(InventoryLocationException.class, exception ->
                        assertThat(exception.code()).isEqualTo(InventoryLocationException.Code.IDEMPOTENCY_CONFLICT));
        assertThatThrownBy(() -> inventory.restock(FULFILLMENT_LOCATION_ID, sold.reservationId(),
                "refund-restock:" + UUID.randomUUID(), List.of(new Item(SELLER_ID, "restock-sku", 2))))
                .isInstanceOf(InventoryLocationException.class);
        // 일부 재입고된 판매분을 통째로 되돌리면 같은 수량이 두 번 판매 가능해지므로 거절한다.
        assertThatThrownBy(() -> inventory.restore(FULFILLMENT_LOCATION_ID, sold.reservationId()))
                .isInstanceOf(InventoryLocationException.class);
        inventory.restock(FULFILLMENT_LOCATION_ID, sold.reservationId(), "refund-restock:" + UUID.randomUUID(),
                List.of(new Item(SELLER_ID, "restock-sku", 1)));
        stock = inventory.findStock(FULFILLMENT_LOCATION_ID, SELLER_ID, "restock-sku");
        assertThat(stock.available()).isEqualTo(3);
        assertThat(stock.claimed()).isZero();
        assertThat(stock.available() + stock.reserved() + stock.claimed()).isEqualTo(stock.initialCapacity());
    }

    @Test
    void availabilityReturnsZeroForAnUnseededSkuAndNotFoundForAnUnknownLocation() throws Exception {
        mockMvc.perform(get("/internal/v1/fulfillment-locations/{locationId}/availability/{sellerId}/{skuId}",
                        FULFILLMENT_LOCATION_ID, SELLER_ID, "unseeded-sku")
                        .header("X-Atlas-Service-Token", SERVICE_TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.available").value(0));
        mockMvc.perform(get("/internal/v1/fulfillment-locations/{locationId}/availability/{sellerId}/{skuId}",
                        UUID.randomUUID(), SELLER_ID, "unseeded-sku")
                        .header("X-Atlas-Service-Token", SERVICE_TOKEN))
                .andExpect(status().isNotFound());
    }

    private InventoryReservationRequest request(UUID orderId, Item... items) {
        return new InventoryReservationRequest(orderId, List.of(items));
    }

    private void seedStock(String skuId, long capacity) {
        seedStock(SELLER_ID, skuId, capacity);
    }

    private void seedStock(UUID sellerId, String skuId, long capacity) {
        inventory.setInitialCapacity(FULFILLMENT_LOCATION_ID, sellerId, skuId,
                new LocationStockRequest(sellerId, capacity));
    }

    private void assertStock(String skuId, long initial, long available, long reserved) {
        var stock = inventory.findStock(FULFILLMENT_LOCATION_ID, SELLER_ID, skuId);
        assertThat(stock.initialCapacity()).isEqualTo(initial);
        assertThat(stock.available()).isEqualTo(available);
        assertThat(stock.reserved()).isEqualTo(reserved);
        assertThat(stock.claimed()).isZero();
        assertThat(stock.available() + stock.reserved() + stock.claimed()).isEqualTo(initial);
    }

    private String reservationPath() {
        return "/internal/v1/fulfillment-locations/" + FULFILLMENT_LOCATION_ID + "/reservations";
    }

    private String runTransition(CountDownLatch start, Runnable operation) throws InterruptedException {
        if (!start.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("Start gate timed out");
        try {
            operation.run();
            return "APPLIED";
        } catch (InventoryLocationException exception) {
            if (exception.code() != InventoryLocationException.Code.INVALID_STATE) throw exception;
            return "CONFLICT";
        }
    }
}
