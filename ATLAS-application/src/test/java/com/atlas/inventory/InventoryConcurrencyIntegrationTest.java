package com.atlas.inventory;

import com.atlas.campaign.dto.request.RegisterSellerSkuRequest;
import com.atlas.campaign.service.SellerService;
import com.atlas.campaign.service.SellerSkuService;
import com.atlas.inventory.dto.request.ReserveInventoryRequest;
import com.atlas.inventory.dto.response.InventoryPoolResponse;
import com.atlas.inventory.dto.response.ReservationResponse;
import com.atlas.inventory.exception.InventoryException;
import com.atlas.inventory.persistence.state.ReservationStatus;
import com.atlas.inventory.service.InventoryService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
@SpringBootTest
class InventoryConcurrencyIntegrationTest {

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

    @Autowired
    private InventoryService inventory;
    @Autowired
    private SellerService sellers;

    @Autowired
    private SellerSkuService sellerSkus;

    @Test
    void concurrentReservationsNeverOversellAndPreserveConservation() throws Exception {
        UUID sellerId = sellers.create().sellerId();
        UUID campaignIdA = UUID.randomUUID();
        UUID campaignIdB = UUID.randomUUID();
        String sku = "sku-" + UUID.randomUUID();
        int capacity = 12;
        sellerSkus.register(sellerId, new RegisterSellerSkuRequest(sku, capacity, capacity));

        try (var executor = Executors.newFixedThreadPool(32)) {
            List<Callable<Boolean>> attempts = new ArrayList<>();
            for (int i = 0; i < 64; i++) {
                UUID campaignId = i % 2 == 0 ? campaignIdA : campaignIdB;
                UUID orderId = UUID.randomUUID();
                attempts.add(() -> {
                    try {
                        inventory.reserve(new ReserveInventoryRequest(orderId, campaignId, sellerId, sku, 1));
                        return true;
                    } catch (InventoryException soldOut) {
                        assertThat(soldOut.code()).isEqualTo(InventoryException.Code.UNAVAILABLE);
                        return false;
                    }
                });
            }
            List<Future<Boolean>> results = executor.invokeAll(attempts);
            long accepted = 0;
            for (Future<Boolean> result : results) {
                if (result.get()) accepted++;
            }
            assertThat(accepted).isEqualTo(capacity);
        }

        InventoryPoolResponse view = inventory.find(sellerId, sku);
        assertThat(view.available()).isZero();
        assertThat(view.reserved()).isEqualTo(capacity);
        assertThat(view.claimed()).isZero();
        assertConserved(view);
    }

    @Test
    void concurrentClaimAndReleaseMaintainConservationAndAreIdempotent() throws Exception {
        UUID sellerId = sellers.create().sellerId();
        UUID campaignIdA = UUID.randomUUID();
        UUID campaignIdB = UUID.randomUUID();
        String sku = "sku-" + UUID.randomUUID();
        sellerSkus.register(sellerId, new RegisterSellerSkuRequest(sku, 2, 2));
        UUID claimOrder = UUID.randomUUID();
        UUID releaseOrder = UUID.randomUUID();
        assertThat(inventory.reserve(new ReserveInventoryRequest(claimOrder, campaignIdA, sellerId, sku, 1)).status())
                .isEqualTo(ReservationStatus.RESERVED);
        inventory.reserve(new ReserveInventoryRequest(claimOrder, campaignIdA, sellerId, sku, 1));
        inventory.reserve(new ReserveInventoryRequest(releaseOrder, campaignIdB, sellerId, sku, 1));

        try (var executor = Executors.newFixedThreadPool(8)) {
            List<Callable<ReservationResponse>> transitions = List.of(
                    () -> inventory.claim(claimOrder),
                    () -> inventory.claim(claimOrder),
                    () -> inventory.release(releaseOrder),
                    () -> inventory.release(releaseOrder));
            for (Future<ReservationResponse> result : executor.invokeAll(transitions)) {
                result.get();
            }
        }

        assertThat(inventory.claim(claimOrder).status()).isEqualTo(ReservationStatus.CLAIMED);
        assertThat(inventory.release(releaseOrder).status()).isEqualTo(ReservationStatus.RELEASED);
        InventoryPoolResponse view = inventory.find(sellerId, sku);
        assertThat(view.available()).isEqualTo(1);
        assertThat(view.reserved()).isZero();
        assertThat(view.claimed()).isEqualTo(1);
        assertConserved(view);
        assertThatThrownBy(() -> inventory.claim(releaseOrder))
                .isInstanceOfSatisfying(InventoryException.class, exception ->
                        assertThat(exception.code()).isEqualTo(InventoryException.Code.CONFLICT));
        assertThatThrownBy(() -> inventory.release(claimOrder))
                .isInstanceOfSatisfying(InventoryException.class, exception ->
                        assertThat(exception.code()).isEqualTo(InventoryException.Code.CONFLICT));
    }

    private static void assertConserved(InventoryPoolResponse view) {
        assertThat(view.available() + view.reserved() + view.claimed()).isEqualTo(view.initialCapacity());
    }
}
