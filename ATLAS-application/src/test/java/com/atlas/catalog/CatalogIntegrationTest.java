package com.atlas.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.atlas.catalog.dto.request.CreateProductRequest;
import com.atlas.catalog.dto.request.RegisterSellerSkuRequest;
import com.atlas.catalog.dto.response.ProductListingPageResponse;
import com.atlas.catalog.persistence.entity.Product;
import com.atlas.catalog.repository.ProductRepository;
import com.atlas.catalog.service.ProductService;
import com.atlas.catalog.service.SellerService;
import tools.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@AutoConfigureMockMvc
@SpringBootTest
@Testcontainers
class CatalogIntegrationTest {
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

    @Autowired MockMvc mockMvc;
    @Autowired ProductRepository products;
    @Autowired ProductService productService;
    @Autowired SellerService sellerService;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper objectMapper;

    @Test
    void repositoryPagesAreBoundedAndContinueInD067OrderWithFilters() throws Exception {
        UUID firstSeller = sellerService.createSeller().sellerId();
        UUID secondSeller = sellerService.createSeller().sellerId();
        List<Product> seeded = new ArrayList<>();
        seeded.add(create(firstSeller, " Needle one"));
        seeded.add(create(firstSeller, "Needle two"));
        seeded.add(create(firstSeller, "Needle three"));
        seeded.add(create(secondSeller, "Needle four"));
        create(secondSeller, "Other product");
        List<Product> expected = seeded.stream()
                .sorted(Comparator.comparing(Product::getSellerId).thenComparing(Product::getProductId)).toList();
        List<Product> firstPage = products.findListingPage(null, null, "needle", null, null, 2);
        assertThat(firstPage).hasSize(2);
        Product lastFirstPage = firstPage.getLast();
        List<Product> secondPage = products.findListingPage(null, null, "needle", lastFirstPage.getSellerId(),
                lastFirstPage.getProductId(), 2);
        assertThat(secondPage).hasSize(2);
        Product lastSecondPage = secondPage.getLast();
        List<Product> thirdPage = products.findListingPage(null, null, "needle", lastSecondPage.getSellerId(),
                lastSecondPage.getProductId(), 2);
        List<UUID> expectedIds = expected.stream().map(Product::getProductId).toList();
        assertThat(firstPage).extracting(Product::getProductId).containsExactlyElementsOf(expectedIds.subList(0, 2));
        assertThat(secondPage).extracting(Product::getProductId).containsExactlyElementsOf(expectedIds.subList(2, 4));
        assertThat(thirdPage).isEmpty();
        assertThat(products.findListingPage(firstSeller, null, "needle", null, null, 10))
                .hasSize(3).allSatisfy(product -> assertThat(product.getSellerId()).isEqualTo(firstSeller));
        productService.registerSku(expected.get(0).getSellerId(), expected.get(0).getProductId(),
                new RegisterSellerSkuRequest("CURRENT-PAGE-SKU", 100, 1));
        productService.registerSku(expected.get(2).getSellerId(), expected.get(2).getProductId(),
                new RegisterSellerSkuRequest("LOOKAHEAD-SKU", 100, 1));
        var servicePage = productService.listProductsPage(null, null, " NEEDLE ", 2, null);
        assertThat(servicePage.items()).hasSize(2);
        assertThat(servicePage.nextCursor()).isNotBlank();
        assertThat(servicePage.items().getFirst().variants()).extracting(variant -> variant.skuId())
                .containsExactly("CURRENT-PAGE-SKU");
        assertThat(servicePage.items().get(1).variants()).isEmpty();
        var serviceNextPage = productService.listProductsPage(
                null, null, " NEEDLE ", 2, servicePage.nextCursor());
        assertThat(serviceNextPage.items()).extracting(item -> item.product().productId())
                .containsExactlyElementsOf(expectedIds.subList(2, 4));
        assertThat(serviceNextPage.nextCursor()).isNull();

        long countBeforeGet = products.count();
        String firstHttpBody = mockMvc.perform(get("/api/products").param("query", " NEEDLE ").param("limit", "2"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        ProductListingPageResponse firstHttpPage =
                objectMapper.readValue(firstHttpBody, ProductListingPageResponse.class);
        assertThat(firstHttpPage.items()).extracting(item -> item.product().productId())
                .containsExactlyElementsOf(expectedIds.subList(0, 2));
        assertThat(firstHttpPage.nextCursor()).isNotBlank();
        String nextHttpBody = mockMvc.perform(get("/api/products")
                        .param("query", " NEEDLE ")
                        .param("limit", "2")
                        .param("cursor", firstHttpPage.nextCursor()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        ProductListingPageResponse nextHttpPage = objectMapper.readValue(nextHttpBody, ProductListingPageResponse.class);
        assertThat(nextHttpPage.items()).extracting(item -> item.product().productId())
                .containsExactlyElementsOf(expectedIds.subList(2, 4));
        assertThat(nextHttpPage.nextCursor()).isNull();
        mockMvc.perform(get("/api/products")
                        .param("query", "OTHER")
                        .param("limit", "2")
                        .param("cursor", firstHttpPage.nextCursor()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.detail").value("요청 정보를 확인해 주세요."));
        assertThat(products.count()).isEqualTo(countBeforeGet);
    }

    @Test
    void repositoryCursorMatchesJavaUuidNaturalOrderAndSearchTreatsWildcardsLiterally() {
        UUID sellerId = sellerService.createSeller().sellerId();
        UUID categoryId = jdbc.queryForObject("SELECT category_id FROM product_category WHERE seller_id=? AND code=?",
                UUID.class, sellerId, "UNCATEGORIZED");
        UUID negativeLeastSignificantBits = UUID.fromString("00000000-0000-0000-8000-000000000000");
        UUID positiveLeastSignificantBits = UUID.fromString("00000000-0000-0000-7fff-ffffffffffff");
        jdbc.update("INSERT INTO product (product_id, seller_id, category_id, name, description) VALUES (?, ?, ?, ?, ?)",
                positiveLeastSignificantBits, sellerId, categoryId, "literal % value", "description");
        jdbc.update("INSERT INTO product (product_id, seller_id, category_id, name, description) VALUES (?, ?, ?, ?, ?)",
                negativeLeastSignificantBits, sellerId, categoryId, "literal wildcard", "description");
        jdbc.update("""
                INSERT INTO product_channel_availability (product_id, sales_channel, available) VALUES
                    (?, 'ONLINE_STORE', TRUE), (?, 'CAMPAIGN', TRUE),
                    (?, 'ONLINE_STORE', TRUE), (?, 'CAMPAIGN', TRUE)
                """, negativeLeastSignificantBits, negativeLeastSignificantBits,
                positiveLeastSignificantBits, positiveLeastSignificantBits);
        List<Product> firstPage = products.findListingPage(sellerId, null, "", null, null, 1);
        assertThat(firstPage).extracting(Product::getProductId).containsExactly(negativeLeastSignificantBits);
        List<Product> secondPage = products.findListingPage(sellerId, null, "", sellerId,
                negativeLeastSignificantBits, 1);
        assertThat(secondPage).extracting(Product::getProductId).containsExactly(positiveLeastSignificantBits);
        assertThat(products.findListingPage(sellerId, null, "%", null, null, 10))
                .extracting(Product::getProductId).containsExactly(positiveLeastSignificantBits);
    }

    @Test
    void rollsBackSellerSkuWhenInventoryPoolCreationFails() {
        UUID sellerId = sellerService.createSeller().sellerId();
        UUID productId = productService.createProduct(sellerId,
                new CreateProductRequest("atomic registration", null)).productId();
        String successfulSku = "atomic-success-" + UUID.randomUUID();

        productService.registerSku(sellerId, productId, new RegisterSellerSkuRequest(successfulSku, 100, 2));

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM seller_sku WHERE seller_id = ? AND sku_id = ?",
                Integer.class, sellerId, successfulSku)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM inventory_pool WHERE seller_id = ? AND sku_id = ?",
                Integer.class, sellerId, successfulSku)).isEqualTo(1);

        String failingSku = "atomicity-failure-" + UUID.randomUUID();
        jdbc.execute("""
                CREATE OR REPLACE FUNCTION fail_catalog_inventory_pool_insert() RETURNS trigger AS $$
                BEGIN
                    IF NEW.sku_id LIKE 'atomicity-failure-%' THEN
                        RAISE EXCEPTION 'simulated inventory pool insert failure';
                    END IF;
                    RETURN NEW;
                END;
                $$ LANGUAGE plpgsql
                """);
        jdbc.execute("""
                CREATE TRIGGER fail_catalog_inventory_pool_insert_trigger
                BEFORE INSERT ON inventory_pool
                FOR EACH ROW EXECUTE FUNCTION fail_catalog_inventory_pool_insert()
                """);
        try {
            assertThatThrownBy(() -> productService.registerSku(sellerId, productId,
                    new RegisterSellerSkuRequest(failingSku, 100, 2)))
                    .isInstanceOf(RuntimeException.class)
                    .hasStackTraceContaining("simulated inventory pool insert failure");
        } finally {
            jdbc.execute("DROP TRIGGER IF EXISTS fail_catalog_inventory_pool_insert_trigger ON inventory_pool");
            jdbc.execute("DROP FUNCTION IF EXISTS fail_catalog_inventory_pool_insert()");
        }

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM seller_sku WHERE seller_id = ? AND sku_id = ?",
                Integer.class, sellerId, failingSku)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM inventory_pool WHERE seller_id = ? AND sku_id = ?",
                Integer.class, sellerId, failingSku)).isZero();
    }

    @Test
    void sellerCanScheduleAndClearSkuSalePriceThroughHttp() throws Exception {
        UUID sellerId = sellerService.createSeller().sellerId();
        UUID productId = productService.createProduct(sellerId,
                new CreateProductRequest("sale-price-" + UUID.randomUUID(), "description")).productId();
        String skuId = "sale-sku-" + UUID.randomUUID();
        productService.registerSku(sellerId, productId, new RegisterSellerSkuRequest(skuId, 1000, 1));
        Instant startsAt = Instant.now().minusSeconds(60);
        Instant endsAt = Instant.now().plusSeconds(3600);
        String path = "/api/sellers/{sellerId}/products/{productId}/skus/{skuId}/sale-price";
        UUID otherSellerId = sellerService.createSeller().sellerId();
        mockMvc.perform(put(path, otherSellerId, productId, skuId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"salePriceWon":800,"startsAt":"%s","endsAt":"%s"}
                                """.formatted(startsAt, endsAt)))
                .andExpect(status().isNotFound());

        mockMvc.perform(put(path, sellerId, productId, skuId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"salePriceWon":800,"startsAt":"%s","endsAt":"%s"}
                                """.formatted(startsAt, startsAt)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));

        mockMvc.perform(put(path, sellerId, productId, skuId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"salePriceWon":800,"startsAt":"%s","endsAt":"%s"}
                                """.formatted(startsAt, endsAt)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.priceWon").value(1000))
                .andExpect(jsonPath("$.salePriceWon").value(800))
                .andExpect(jsonPath("$.effectivePriceWon").value(800));

        mockMvc.perform(get("/api/products/{productId}", productId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.variants[0].priceWon").value(1000))
                .andExpect(jsonPath("$.variants[0].salePriceWon").value(800))
                .andExpect(jsonPath("$.variants[0].effectivePriceWon").value(800));

        mockMvc.perform(put(path, sellerId, productId, skuId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"salePriceWon":1000,"startsAt":"%s","endsAt":"%s"}
                                """.formatted(startsAt, endsAt)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));

        mockMvc.perform(put("/api/sellers/{sellerId}/products/{productId}/skus/{skuId}/price",
                        sellerId, productId, skuId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"priceWon\":700}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
        mockMvc.perform(delete(path, sellerId, productId, skuId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.priceWon").value(1000))
                .andExpect(jsonPath("$.effectivePriceWon").value(1000));
    }

    @Test
    void sellerControlsProductOrderabilityAndPublicVisibility() throws Exception {
        UUID sellerId = sellerService.createSeller().sellerId();
        UUID productId = productService.createProduct(
                sellerId, new CreateProductRequest("orderability-" + UUID.randomUUID(), "description")).productId();
        String base = "/api/sellers/" + sellerId + "/products/" + productId;

        mockMvc.perform(put(base + "/channel-availability")
                        .contentType("application/json")
                        .content("{\"channel\":\"ONLINE_STORE\"}"))
                .andExpect(status().isBadRequest());

        mockMvc.perform(put(base + "/channel-availability")
                        .contentType("application/json")
                        .content("{\"channel\":\"ONLINE_STORE\",\"available\":false}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.availableChannels").isArray())
                .andExpect(jsonPath("$.availableChannels.length()").value(1))
                .andExpect(jsonPath("$.availableChannels[0]").value("CAMPAIGN"));
        mockMvc.perform(get("/api/products").param("sellerId", sellerId.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items").isEmpty());
        mockMvc.perform(get("/api/products/" + productId)).andExpect(status().isNotFound());

        mockMvc.perform(put(base + "/channel-availability")
                        .contentType("application/json")
                        .content("{\"channel\":\"ONLINE_STORE\",\"available\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.availableChannels[0]").value("ONLINE_STORE"))
                .andExpect(jsonPath("$.availableChannels[1]").value("CAMPAIGN"));
        mockMvc.perform(get("/api/products").param("sellerId", sellerId.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].product.availableChannels[0]").value("ONLINE_STORE"))
                .andExpect(jsonPath("$.items[0].product.availableChannels[1]").value("CAMPAIGN"));

        mockMvc.perform(put(base + "/status")
                        .contentType("application/json")
                        .content("{\"status\":\"INACTIVE\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("INACTIVE"));
        mockMvc.perform(get("/api/products").param("sellerId", sellerId.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items").isEmpty());
        mockMvc.perform(get("/api/products/" + productId)).andExpect(status().isNotFound());

        mockMvc.perform(get("/api/sellers/" + sellerId + "/products"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1));
        mockMvc.perform(put(base + "/status")
                        .contentType("application/json")
                        .content("{\"status\":\"ACTIVE\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACTIVE"));
        mockMvc.perform(get("/api/products").param("sellerId", sellerId.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1));
        mockMvc.perform(get("/api/products/" + productId)).andExpect(status().isOk());
    }

    @Test
    void publicAndSellerCursorsCannotCrossListingScopes() throws Exception {
        UUID sellerId = sellerService.createSeller().sellerId();
        productService.createProduct(sellerId,
                new CreateProductRequest("scope-active-1-" + UUID.randomUUID(), "description"));
        productService.createProduct(sellerId,
                new CreateProductRequest("scope-active-2-" + UUID.randomUUID(), "description"));
        UUID inactiveProduct = productService.createProduct(sellerId,
                new CreateProductRequest("scope-inactive-" + UUID.randomUUID(), "description")).productId();
        mockMvc.perform(put("/api/sellers/{sellerId}/products/{productId}/status", sellerId, inactiveProduct)
                        .contentType("application/json")
                        .content("{\"status\":\"INACTIVE\"}"))
                .andExpect(status().isOk());

        ProductListingPageResponse publicPage = objectMapper.readValue(
                mockMvc.perform(get("/api/products").param("sellerId", sellerId.toString()).param("limit", "1"))
                        .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(),
                ProductListingPageResponse.class);
        ProductListingPageResponse sellerPage = objectMapper.readValue(
                mockMvc.perform(get("/api/sellers/{sellerId}/products", sellerId).param("limit", "1"))
                        .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(),
                ProductListingPageResponse.class);
        assertThat(publicPage.nextCursor()).isNotBlank();
        assertThat(sellerPage.nextCursor()).isNotBlank();

        mockMvc.perform(get("/api/sellers/{sellerId}/products", sellerId)
                        .param("limit", "1").param("cursor", publicPage.nextCursor()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
        mockMvc.perform(get("/api/products").param("sellerId", sellerId.toString())
                        .param("limit", "1").param("cursor", sellerPage.nextCursor()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }

    private Product create(UUID sellerId, String name) {
        UUID productId = productService.createProduct(sellerId, new CreateProductRequest(name, "description"))
                .productId();
        return products.findById(productId).orElseThrow();
    }
    @Test
    void sellerCategoriesAssignProductsAndFilterWithBoundCursors() throws Exception {
        UUID sellerId = sellerService.createSeller().sellerId();
        UUID otherSellerId = sellerService.createSeller().sellerId();
        String defaultCategory = mockMvc.perform(get("/api/sellers/{sellerId}/product-categories", sellerId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].code").value("UNCATEGORIZED"))
                .andExpect(jsonPath("$[0].name").value("미분류"))
                .andReturn().getResponse().getContentAsString();
        String categoryBody = mockMvc.perform(post("/api/sellers/{sellerId}/product-categories", sellerId)
                        .contentType("application/json").content("{\"code\":\"TEA\",\"name\":\"Tea\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        UUID categoryId = UUID.fromString(objectMapper.readTree(categoryBody).get("categoryId").stringValue());
        mockMvc.perform(post("/api/sellers/{sellerId}/product-categories", sellerId)
                        .contentType("application/json").content("{\"code\":\"TEA\",\"name\":\"Duplicate\"}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("CONFLICT"));
        mockMvc.perform(put("/api/sellers/{sellerId}/product-categories/{categoryId}", sellerId, categoryId)
                        .contentType("application/json").content("{\"name\":\"Tea leaves\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.name").value("Tea leaves"));
        mockMvc.perform(get("/api/sellers/{sellerId}/product-categories", sellerId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].code").value("TEA"))
                .andExpect(jsonPath("$[1].code").value("UNCATEGORIZED"));
        String productBody = mockMvc.perform(post("/api/sellers/{sellerId}/products", sellerId)
                        .contentType("application/json")
                        .content("{\"name\":\"Tea one\",\"description\":\"one\",\"categoryId\":\""
                                + categoryId + "\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.categoryId").value(categoryId.toString()))
                .andReturn().getResponse().getContentAsString();
        UUID productId = UUID.fromString(objectMapper.readTree(productBody).get("productId").stringValue());
        String skuId = "catalog-category-" + UUID.randomUUID();
        productService.registerSku(sellerId, productId, new RegisterSellerSkuRequest(skuId, 100, 3));
        String inventoryQuery = """
                SELECT initial_capacity, available, reserved, claimed
                FROM inventory_pool
                WHERE seller_id = ? AND sku_id = ?
                """;
        var inventoryBefore = jdbc.queryForMap(inventoryQuery, sellerId, skuId);
        String defaultId = objectMapper.readTree(defaultCategory).get(0).get("categoryId").stringValue();
        mockMvc.perform(put("/api/sellers/{sellerId}/product-categories/{categoryId}", sellerId, defaultId)
                        .contentType("application/json").content("{\"name\":\"Changed default\"}"))
                .andExpect(status().isConflict());
        mockMvc.perform(post("/api/sellers/{sellerId}/products", sellerId)
                        .contentType("application/json")
                        .content("{\"name\":\"Tea two\",\"description\":\"two\",\"categoryId\":\""
                                + categoryId + "\"}"))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/api/sellers/{sellerId}/products", sellerId)
                        .contentType("application/json")
                        .content("{\"name\":\"Tea default\",\"description\":\"default\"}"))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.categoryId").value(defaultId));
        mockMvc.perform(get("/api/products").param("categoryId", categoryId.toString()))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
        mockMvc.perform(get("/api/sellers/{sellerId}/products", otherSellerId)
                        .param("categoryId", categoryId.toString()))
                .andExpect(status().isNotFound());
        String globalSellerBody = mockMvc.perform(get("/api/products")
                        .param("sellerId", sellerId.toString()).param("categoryId", categoryId.toString()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        ProductListingPageResponse globalSellerPage =
                objectMapper.readValue(globalSellerBody, ProductListingPageResponse.class);
        assertThat(globalSellerPage.items()).hasSize(2).allSatisfy(item ->
                assertThat(item.product().categoryId()).isEqualTo(categoryId));
        String pageBody = mockMvc.perform(get("/api/sellers/{sellerId}/products", sellerId)
                        .param("categoryId", categoryId.toString()).param("query", "TEA").param("limit", "1"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        ProductListingPageResponse page = objectMapper.readValue(pageBody, ProductListingPageResponse.class);
        assertThat(page.items()).hasSize(1);
        UUID firstPageProductId = page.items().getFirst().product().productId();
        assertThat(page.items().getFirst().product().categoryId()).isEqualTo(categoryId);
        assertThat(page.nextCursor()).isNotBlank();
        String nextPageBody = mockMvc.perform(get("/api/sellers/{sellerId}/products", sellerId)
                        .param("categoryId", categoryId.toString()).param("query", "TEA").param("limit", "1")
                        .param("cursor", page.nextCursor()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        ProductListingPageResponse nextPage = objectMapper.readValue(nextPageBody, ProductListingPageResponse.class);
        assertThat(nextPage.items()).hasSize(1);
        UUID secondPageProductId = nextPage.items().getFirst().product().productId();
        assertThat(secondPageProductId).isNotEqualTo(firstPageProductId);
        assertThat(List.of(firstPageProductId, secondPageProductId)).contains(productId);
        assertThat(nextPage.items().getFirst().product().categoryId()).isEqualTo(categoryId);
        assertThat(nextPage.nextCursor()).isNull();
        mockMvc.perform(get("/api/sellers/{sellerId}/products", sellerId)
                        .param("categoryId", defaultId).param("limit", "1").param("cursor", page.nextCursor()))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
        mockMvc.perform(get("/api/sellers/{sellerId}/products", sellerId)
                        .param("categoryId", categoryId.toString()).param("query", "Different").param("limit", "1")
                        .param("cursor", page.nextCursor()))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
        mockMvc.perform(put("/api/sellers/{sellerId}/products/{productId}/category", sellerId, productId)
                        .contentType("application/json").content("{\"categoryId\":\"" + defaultId + "\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.categoryId").value(defaultId));
        mockMvc.perform(put("/api/sellers/{sellerId}/products/{productId}/category", otherSellerId, productId)
                        .contentType("application/json").content("{\"categoryId\":\"" + defaultId + "\"}"))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/sellers/{sellerId}/product-categories", sellerId))
                .andExpect(status().isOk());
        assertThat(jdbc.queryForMap(inventoryQuery, sellerId, skuId)).isEqualTo(inventoryBefore);
    }

}
