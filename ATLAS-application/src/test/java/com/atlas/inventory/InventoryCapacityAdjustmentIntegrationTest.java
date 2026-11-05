package com.atlas.inventory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.atlas.catalog.dto.request.CreateProductRequest;
import com.atlas.catalog.dto.request.RegisterSellerSkuRequest;
import com.atlas.catalog.service.ProductService;
import com.atlas.catalog.service.SellerService;
import com.atlas.inventory.dto.request.AdjustInventoryCapacityRequest;
import com.atlas.inventory.dto.request.ReserveInventoryRequest;
import com.atlas.inventory.dto.response.InventoryAdjustmentResponse;
import com.atlas.inventory.dto.response.InventoryPoolResponse;
import com.atlas.inventory.exception.InventoryException;
import com.atlas.inventory.service.InventoryCapacityAdjustmentService;
import com.atlas.inventory.service.InventoryService;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.Callable;
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
class InventoryCapacityAdjustmentIntegrationTest {
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

    @Autowired InventoryCapacityAdjustmentService adjustments;
    @Autowired InventoryService inventory;
    @Autowired SellerService sellers;
    @Autowired ProductService products;
    @Autowired JdbcTemplate jdbc;

    @Test
    void adjustmentReplaysAndRejectsChangedFactsWhilePreservingConservation() {
        UUID sellerId = sellers.createSeller().sellerId();
        String sku = UUID.randomUUID().toString();
        UUID productId = products.createProduct(sellerId,
                new CreateProductRequest("adjustment-" + UUID.randomUUID(), "description")).productId();
        products.registerSku(sellerId, productId, new RegisterSellerSkuRequest(sku, 100, 4));

        inventory.reserve(new ReserveInventoryRequest(UUID.randomUUID(), null, sellerId, sku, 2));
        AdjustInventoryCapacityRequest add = new AdjustInventoryCapacityRequest(5, "restock");
        InventoryAdjustmentResponse applied = adjustments.adjust(sellerId, sku, "adjust-key", add);
        assertThat(applied.pool().initialCapacity()).isEqualTo(9);
        assertThat(applied.pool().available()).isEqualTo(7);
        assertThat(applied.pool().reserved()).isEqualTo(2);
        assertThat(adjustments.adjust(sellerId, sku, "adjust-key", add)).isEqualTo(applied);
        assertThatThrownBy(() -> adjustments.adjust(sellerId, sku, "adjust-key",
                new AdjustInventoryCapacityRequest(6, "restock")))
                .isInstanceOfSatisfying(InventoryException.class,
                        error -> assertThat(error.code()).isEqualTo(InventoryException.Code.CONFLICT));

        InventoryAdjustmentResponse reduce = adjustments.adjust(sellerId, sku, "reduce-key",
                new AdjustInventoryCapacityRequest(-3, "correction"));
        assertThat(reduce.pool().available()).isEqualTo(4);
        assertThat(reduce.pool().reserved()).isEqualTo(2);
        assertThat(reduce.pool().claimed()).isZero();
        assertThat(reduce.pool().available() + reduce.pool().reserved() + reduce.pool().claimed())
                .isEqualTo(reduce.pool().initialCapacity());
        assertThatThrownBy(() -> adjustments.adjust(sellerId, sku, "too-large",
                new AdjustInventoryCapacityRequest(-5, "correction")))
                .isInstanceOf(InventoryException.class);
        assertThatThrownBy(() -> adjustments.adjust(sellerId, sku, "overflow",
                new AdjustInventoryCapacityRequest(Integer.MAX_VALUE, "overflow")))
                .isInstanceOf(RuntimeException.class);
        InventoryPoolResponse afterFailure = inventory.find(sellerId, sku);
        assertThat(afterFailure.initialCapacity()).isEqualTo(6);
        assertThat(afterFailure.available()).isEqualTo(4);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM inventory_capacity_adjustment WHERE seller_id = ? AND sku_id = ? AND idempotency_key = ?", Integer.class, sellerId, sku, "overflow")).isZero();
    }

    @Test
    void concurrentSameKeyChangesCapacityOnlyOnce() throws Exception {
        UUID sellerId = sellers.createSeller().sellerId();
        String sku = UUID.randomUUID().toString();
        UUID productId = products.createProduct(sellerId,
                new CreateProductRequest("concurrent-" + UUID.randomUUID(), "description")).productId();
        products.registerSku(sellerId, productId, new RegisterSellerSkuRequest(sku, 100, 3));
        AdjustInventoryCapacityRequest request = new AdjustInventoryCapacityRequest(2, "restock");
        try (var executor = Executors.newFixedThreadPool(8)) {
        List<Callable<InventoryAdjustmentResponse>> calls = new ArrayList<>();
            for (int i = 0; i < 16; i++) {
                calls.add(() -> adjustments.adjust(sellerId, sku, "concurrent-key", request));
            }
            for (var future : executor.invokeAll(calls)) assertThat(future.get().delta()).isEqualTo(2);
        }
        InventoryPoolResponse pool = inventory.find(sellerId, sku);
        assertThat(pool.initialCapacity()).isEqualTo(5);
        assertThat(pool.available() + pool.reserved() + pool.claimed()).isEqualTo(pool.initialCapacity());
    }
}
