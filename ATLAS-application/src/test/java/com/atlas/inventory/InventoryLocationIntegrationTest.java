package com.atlas.inventory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.atlas.catalog.dto.request.CreateProductRequest;
import com.atlas.catalog.dto.request.RegisterSellerSkuRequest;
import com.atlas.catalog.dto.response.ProductResponse;
import com.atlas.catalog.service.ProductService;
import com.atlas.catalog.service.SellerService;
import com.atlas.inventory.dto.request.CreateFulfillmentLocationRequest;
import com.atlas.inventory.dto.request.ReserveInventoryBatchRequest;
import com.atlas.inventory.dto.request.TransferInventoryRequest;
import com.atlas.inventory.dto.request.UpdateFulfillmentLocationRequest;
import com.atlas.inventory.dto.response.InventoryLocationResponse;
import com.atlas.inventory.dto.response.InventoryTransferResponse;
import com.atlas.inventory.exception.InventoryException;
import com.atlas.inventory.service.InventoryCapacityAdjustmentService;
import com.atlas.inventory.service.InventoryService;
import java.util.List;
import java.util.UUID;
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
class InventoryLocationIntegrationTest {
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

    @Autowired private MockMvc mockMvc;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private SellerService sellers;
    @Autowired private ProductService products;
    @Autowired private InventoryService inventory;
    @Autowired private InventoryCapacityAdjustmentService adjustments;

    @Test
    void managementRoutesAndDefaultAdjustmentCompatibility() throws Exception {
        UUID sellerId = sellers.createSeller().sellerId();
        String sku = createSku(sellerId, 5);
        String sellerRoot = "/api/sellers/" + sellerId;
        String inventoryRoot = sellerRoot + "/inventory";
        String code = "NORTH-" + UUID.randomUUID();

        mockMvc.perform(post(sellerRoot + "/fulfillment-locations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"" + code + "\",\"name\":\"North room\",\"priority\":2}"))
                .andExpect(status().isOk());
        UUID locationId = jdbc.queryForObject(
                "SELECT location_id FROM inventory_location WHERE seller_id=? AND location_code=?",
                UUID.class, sellerId, code);
        mockMvc.perform(post(sellerRoot + "/fulfillment-locations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"" + code + "\",\"name\":\"Duplicate\",\"priority\":3}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CONFLICT"));
        mockMvc.perform(put(sellerRoot + "/fulfillment-locations/{locationId}", UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Missing\",\"priority\":0,\"active\":true}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("INVENTORY_LOCATION_NOT_FOUND"));
        mockMvc.perform(get(sellerRoot + "/fulfillment-locations"))
                .andExpect(status().isOk())
                .andExpect(result -> assertThat(result.getResponse().getContentAsString())
                        .contains("DEFAULT", code, "North room"));
        mockMvc.perform(put(sellerRoot + "/fulfillment-locations/{locationId}", locationId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"North room 2\",\"priority\":0,\"active\":false}"))
                .andExpect(status().isOk())
                .andExpect(result -> assertThat(result.getResponse().getContentAsString())
                        .contains("North room 2", "\"active\":false"));
        mockMvc.perform(get(inventoryRoot + "/locations/{locationId}", locationId))
                .andExpect(status().isOk());

        mockMvc.perform(post(inventoryRoot + "/{sku}/capacity-adjustments", sku)
                        .header("Idempotency-Key", "default-adjust-" + UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"delta\":2,\"reason\":\"legacy route\"}"))
                .andExpect(status().isOk());
        mockMvc.perform(post(inventoryRoot + "/locations/{locationId}/skus/{sku}/capacity-adjustments",
                        locationId, sku)
                        .header("Idempotency-Key", "named-adjust-" + UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"delta\":1,\"reason\":\"named location route\"}"))
                .andExpect(status().isOk());

        UUID defaultId = defaultLocation(sellerId);
        assertThat(jdbc.queryForObject(
                "SELECT initial_capacity FROM inventory_pool WHERE seller_id=? AND sku_id=? AND location_id=?",
                Integer.class, sellerId, sku, defaultId)).isEqualTo(7);
        assertThat(inventory.findLocationInventory(sellerId, locationId)).singleElement()
                .satisfies(stock -> assertThat(stock.initialCapacity()).isEqualTo(1));
    }

    @Test
    void transferReplayConservesCapacityAndRejectsInactiveDestinations() {
        UUID sellerId = sellers.createSeller().sellerId();
        String sku = createSku(sellerId, 8);
        InventoryLocationResponse north = inventory.createLocation(
                new CreateFulfillmentLocationRequest(sellerId, "NORTH-" + UUID.randomUUID(), "North", 1));
        String transferKey = "transfer-" + UUID.randomUUID();
        UUID defaultId = defaultLocation(sellerId);
        TransferInventoryRequest request = new TransferInventoryRequest(sellerId, sku,
                defaultId, north.locationId(), 3, transferKey, "rebalance");

        InventoryTransferResponse first = inventory.transfer(request);
        assertThat(inventory.transfer(request)).isEqualTo(first);
        assertThatThrownBy(() -> inventory.transfer(new TransferInventoryRequest(sellerId, sku,
                request.sourceLocationId(), request.destinationLocationId(), 2, transferKey, "changed")))
                .isInstanceOf(RuntimeException.class);

        assertThat(inventory.findLocationInventory(sellerId, north.locationId())).singleElement()
                .satisfies(stock -> {
                    assertThat(stock.initialCapacity()).isEqualTo(3);
                    assertThat(stock.available()).isEqualTo(3);
                    assertThat(stock.reserved() + stock.claimed() + stock.available())
                            .isEqualTo(stock.initialCapacity());
                });
        assertThat(inventory.find(sellerId, sku).initialCapacity()).isEqualTo(8);
        assertThat(inventory.findLocationInventory(sellerId, defaultId)).singleElement()
                .satisfies(stock -> {
                    assertThat(stock.available()).isEqualTo(5);
                    assertThat(stock.initialCapacity()).isEqualTo(5);
                    assertThat(stock.available() + stock.reserved() + stock.claimed())
                            .isEqualTo(stock.initialCapacity());
                });
        assertThat(jdbc.queryForObject(
                "SELECT SUM(initial_capacity) FROM inventory_pool WHERE seller_id=? AND sku_id=?",
                Integer.class, sellerId, sku)).isEqualTo(8);
        assertThat(jdbc.queryForObject(
                "SELECT SUM(available+reserved+claimed) FROM inventory_pool WHERE seller_id=? AND sku_id=?",
                Integer.class, sellerId, sku)).isEqualTo(8);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM inventory_transfer WHERE seller_id=? AND sku_id=?",
                Integer.class, sellerId, sku)).isEqualTo(1);
        inventory.updateLocation(new UpdateFulfillmentLocationRequest(sellerId, north.locationId(),
                north.name(), north.priority(), false));
        assertThatThrownBy(() -> inventory.transfer(new TransferInventoryRequest(sellerId, sku,
                defaultId, north.locationId(), 1, "inactive-transfer-" + UUID.randomUUID(), "inactive destination")))
                .isInstanceOf(InventoryException.class)
                .satisfies(error -> assertThat(((InventoryException) error).code())
                        .isEqualTo(InventoryException.Code.CONFLICT));
        assertThat(inventory.findLocationInventory(sellerId, defaultId)).singleElement()
                .satisfies(stock -> assertThat(stock.available()).isEqualTo(5));
        assertThat(inventory.findLocationInventory(sellerId, north.locationId())).singleElement()
                .satisfies(stock -> assertThat(stock.available()).isEqualTo(3));
    }

    @Test
    void inactiveLocationIsExcludedFromFulfillmentRouting() {
        UUID sellerId = sellers.createSeller().sellerId();
        String sku = createSku(sellerId, 5);
        InventoryLocationResponse location = inventory.createLocation(new CreateFulfillmentLocationRequest(
                sellerId, "INACTIVE-" + UUID.randomUUID(), "Inactive", 0));
        adjustments.adjust(sellerId, sku, location.locationId(), "stock-location-" + UUID.randomUUID(), 5,
                "stock location");
        inventory.updateLocation(new UpdateFulfillmentLocationRequest(sellerId, location.locationId(),
                location.name(), location.priority(), false));

        assertThat(inventory.findFulfillmentLocation(sellerId, List.of(
                new ReserveInventoryBatchRequest.Item(sellerId, sku, 1))))
                .contains(defaultLocation(sellerId));
    }
    @Test
    void transferHttpRouteUsesIdempotencyKeyAndRejectsChangedReplayFacts() throws Exception {
        UUID sellerId = sellers.createSeller().sellerId();
        String sku = createSku(sellerId, 6);
        InventoryLocationResponse destination = inventory.createLocation(new CreateFulfillmentLocationRequest(
                sellerId, "HTTP-TRANSFER-" + UUID.randomUUID(), "HTTP destination", 0));
        String key = "http-transfer-" + UUID.randomUUID();
        String url = "/api/sellers/" + sellerId + "/inventory/transfers";
        String body = "{\"skuId\":\"" + sku + "\",\"sourceLocationId\":\"" + defaultLocation(sellerId)
                + "\",\"destinationLocationId\":\"" + destination.locationId()
                + "\",\"quantity\":2,\"reason\":\"http transfer\"}";

        mockMvc.perform(post(url).header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk());
        mockMvc.perform(post(url).header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk());
        mockMvc.perform(post(url).header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body.replace("\"quantity\":2", "\"quantity\":1")))
                .andExpect(status().isConflict());
        assertThat(inventory.find(sellerId, sku).initialCapacity()).isEqualTo(6);
        assertThat(inventory.findLocationInventory(sellerId, destination.locationId())).singleElement()
                .satisfies(stock -> assertThat(stock.initialCapacity()).isEqualTo(2));
    }

    @Test
    void aggregateReadsSupportTotalsAbovePerLocationIntegerRange() {
        UUID sellerId = sellers.createSeller().sellerId();
        int perLocationCapacity = 1_500_000_000;
        String sku = createSku(sellerId, perLocationCapacity);
        InventoryLocationResponse secondLocation = inventory.createLocation(new CreateFulfillmentLocationRequest(
                sellerId, "SECOND-" + UUID.randomUUID(), "Second", 1));

        adjustments.adjust(sellerId, sku, secondLocation.locationId(),
                "aggregate-range-" + UUID.randomUUID(), perLocationCapacity, "seed");

        assertThat(inventory.find(sellerId, sku).initialCapacity()).isEqualTo(3_000_000_000L);
        assertThat(inventory.find(sellerId, sku).available()).isEqualTo(3_000_000_000L);
        assertThat(inventory.findAll(sellerId)).singleElement()
                .satisfies(pool -> assertThat(pool.initialCapacity()).isEqualTo(3_000_000_000L));
    }

    private String createSku(UUID sellerId, int capacity) {
        ProductResponse product = products.createProduct(sellerId,
                new CreateProductRequest("location-test-" + UUID.randomUUID(), null));
        String sku = "location-sku-" + UUID.randomUUID();
        products.registerSku(sellerId, product.productId(), new RegisterSellerSkuRequest(sku, 100, capacity));
        return sku;
    }

    private UUID defaultLocation(UUID sellerId) {
        return jdbc.queryForObject("SELECT location_id FROM inventory_location WHERE seller_id=? AND location_code='DEFAULT'",
                UUID.class, sellerId);
    }
}
