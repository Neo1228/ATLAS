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
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Testcontainers
@SpringBootTest
@AutoConfigureMockMvc

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
    @Autowired MockMvc mockMvc;

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
    @Test
    void adjustmentFailuresHaveStableHttpProblemCodesAndDoNotWriteAudit() throws Exception {
        UUID sellerId = sellers.createSeller().sellerId();
        String sku = UUID.randomUUID().toString();
        UUID productId = products.createProduct(sellerId,
                new CreateProductRequest("http-adjustment-" + UUID.randomUUID(), "description")).productId();
        products.registerSku(sellerId, productId, new RegisterSellerSkuRequest(sku, 100, 2));

        mockMvc.perform(post("/api/sellers/{sellerId}/inventory/{sku}/capacity-adjustments",
                        UUID.randomUUID(), sku)
                        .header("Idempotency-Key", "missing-pool")
                        .contentType("application/json").content("{\"delta\":1,\"reason\":\"restock\"}"))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("INVENTORY_POOL_NOT_FOUND"));
        mockMvc.perform(post("/api/sellers/{sellerId}/inventory/{sku}/capacity-adjustments", sellerId, sku)
                        .header("Idempotency-Key", "insufficient")
                        .contentType("application/json").content("{\"delta\":-3,\"reason\":\"correction\"}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("INSUFFICIENT_STOCK"));
        mockMvc.perform(post("/api/sellers/{sellerId}/inventory/{sku}/capacity-adjustments", sellerId, sku)
                        .header("Idempotency-Key", "minimum")
                        .contentType("application/json").content("{\"delta\":-2147483648,\"reason\":\"correction\"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
        mockMvc.perform(post("/api/sellers/{sellerId}/inventory/{sku}/capacity-adjustments", sellerId, sku)
                        .header("Idempotency-Key", "maximum")
                        .contentType("application/json").content("{\"delta\":2147483647,\"reason\":\"restock\"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
        mockMvc.perform(post("/api/sellers/{sellerId}/inventory/{sku}/capacity-adjustments", sellerId, sku)
                        .header("Idempotency-Key", "x".repeat(257))
                        .contentType("application/json").content("{\"delta\":1,\"reason\":\"restock\"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_REQUEST"));

        assertThat(inventory.find(sellerId, sku).initialCapacity()).isEqualTo(2);
        assertThat(inventory.find(sellerId, sku).available()).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM inventory_capacity_adjustment WHERE seller_id = ? AND sku_id = ?",
                Integer.class, sellerId, sku)).isZero();
    }
    @Test
    void differentKeysCannotOverdrawAvailableCapacity() throws Exception {
        UUID sellerId = sellers.createSeller().sellerId();
        String sku = UUID.randomUUID().toString();
        UUID productId = products.createProduct(sellerId,
                new CreateProductRequest("different-keys-" + UUID.randomUUID(), "description")).productId();
        products.registerSku(sellerId, productId, new RegisterSellerSkuRequest(sku, 100, 3));
        try (var executor = Executors.newFixedThreadPool(2)) {
            var outcomes = executor.invokeAll(List.of(
                    (Callable<Boolean>) () -> succeeds(sellerId, sku, "first-key"),
                    () -> succeeds(sellerId, sku, "second-key")));
            assertThat(outcomes.get(0).get() ^ outcomes.get(1).get()).isTrue();
        }
        InventoryPoolResponse pool = inventory.find(sellerId, sku);
        assertThat(pool.available()).isEqualTo(1);
        assertThat(pool.initialCapacity()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM inventory_capacity_adjustment WHERE seller_id = ? AND sku_id = ?",
                Integer.class, sellerId, sku)).isEqualTo(1);
    }

    private boolean succeeds(UUID sellerId, String sku, String key) {
        try {
            adjustments.adjust(sellerId, sku, key, new AdjustInventoryCapacityRequest(-2, "correction"));
            return true;
        } catch (InventoryException insufficient) {
            if (insufficient.code() != InventoryException.Code.INSUFFICIENT_STOCK) throw insufficient;
            return false;
        }
    }
}
