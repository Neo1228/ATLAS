package com.atlas.inventory.internal;

import com.atlas.inventory.CreateInventoryPoolCommand;
import com.atlas.inventory.InventoryApi;
import com.atlas.inventory.InventoryView;
import com.atlas.inventory.ReservationStatus;
import com.atlas.inventory.ReservationView;
import com.atlas.inventory.ReserveInventoryCommand;

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
class InventoryApiConcurrencyTest {

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
    private InventoryApi inventory;

    @Test
    void concurrentReservationsNeverOversellAndPreserveConservation() throws Exception {
        UUID campaignId = UUID.randomUUID();
        String sku = "sku-" + UUID.randomUUID();
        int capacity = 12;
        inventory.createPool(new CreateInventoryPoolCommand(campaignId, sku, capacity));

        try (var executor = Executors.newFixedThreadPool(32)) {
            List<Callable<Boolean>> attempts = new ArrayList<>();
            for (int i = 0; i < 64; i++) {
                UUID orderId = UUID.randomUUID();
                attempts.add(() -> {
                    try {
                        inventory.reserve(new ReserveInventoryCommand(orderId, campaignId, sku, 1));
                        return true;
                    } catch (IllegalStateException soldOut) {
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

        InventoryView view = inventory.find(campaignId, sku);
        assertThat(view.available()).isZero();
        assertThat(view.reserved()).isEqualTo(capacity);
        assertThat(view.claimed()).isZero();
        assertConserved(view);
    }

    @Test
    void concurrentClaimAndReleaseMaintainConservationAndAreIdempotent() throws Exception {
        UUID campaignId = UUID.randomUUID();
        String sku = "sku-" + UUID.randomUUID();
        inventory.createPool(new CreateInventoryPoolCommand(campaignId, sku, 2));
        UUID claimOrder = UUID.randomUUID();
        UUID releaseOrder = UUID.randomUUID();
        assertThat(inventory.reserve(new ReserveInventoryCommand(claimOrder, campaignId, sku, 1)).status())
                .isEqualTo(ReservationStatus.RESERVED);
        inventory.reserve(new ReserveInventoryCommand(claimOrder, campaignId, sku, 1));
        inventory.reserve(new ReserveInventoryCommand(releaseOrder, campaignId, sku, 1));

        try (var executor = Executors.newFixedThreadPool(8)) {
            List<Callable<ReservationView>> transitions = List.of(
                    () -> inventory.claim(claimOrder),
                    () -> inventory.claim(claimOrder),
                    () -> inventory.release(releaseOrder),
                    () -> inventory.release(releaseOrder));
            for (Future<ReservationView> result : executor.invokeAll(transitions)) {
                result.get();
            }
        }

        assertThat(inventory.claim(claimOrder).status()).isEqualTo(ReservationStatus.CLAIMED);
        assertThat(inventory.release(releaseOrder).status()).isEqualTo(ReservationStatus.RELEASED);
        InventoryView view = inventory.find(campaignId, sku);
        assertThat(view.available()).isEqualTo(1);
        assertThat(view.reserved()).isZero();
        assertThat(view.claimed()).isEqualTo(1);
        assertConserved(view);
        assertThatThrownBy(() -> inventory.claim(releaseOrder)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> inventory.release(claimOrder)).isInstanceOf(IllegalStateException.class);
    }

    private static void assertConserved(InventoryView view) {
        assertThat(view.available() + view.reserved() + view.claimed()).isEqualTo(view.initialCapacity());
    }
}
