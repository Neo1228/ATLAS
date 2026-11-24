package com.atlas.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.atlas.catalog.dto.response.CatalogResponse;
import com.atlas.catalog.dto.request.ProductRequest;
import com.atlas.catalog.exception.CatalogException;
import com.atlas.catalog.persistence.entity.Product;
import com.atlas.catalog.repository.ProductRepository;
import com.atlas.catalog.service.ProductService;
import com.atlas.catalog.service.SellerService;
import tools.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import com.atlas.catalog.model.ProductSalesChannel;
import com.atlas.catalog.model.ProductStatus;
import java.sql.Array;
import java.sql.SQLException;
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

@AutoConfigureMockMvc(addFilters = false)
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
                new ProductRequest.RegisterSellerSku("CURRENT-PAGE-SKU", 100, 1));
        productService.registerSku(expected.get(2).getSellerId(), expected.get(2).getProductId(),
                new ProductRequest.RegisterSellerSku("LOOKAHEAD-SKU", 100, 1));
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
        CatalogResponse.ProductListingPage firstHttpPage =
                objectMapper.readValue(firstHttpBody, CatalogResponse.ProductListingPage.class);
        assertThat(firstHttpPage.items()).extracting(item -> item.product().productId())
                .containsExactlyElementsOf(expectedIds.subList(0, 2));
        assertThat(firstHttpPage.nextCursor()).isNotBlank();
        String nextHttpBody = mockMvc.perform(get("/api/products")
                        .param("query", " NEEDLE ")
                        .param("limit", "2")
                        .param("cursor", firstHttpPage.nextCursor()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        CatalogResponse.ProductListingPage nextHttpPage = objectMapper.readValue(nextHttpBody, CatalogResponse.ProductListingPage.class);
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
    void keywordSearchTreatsLikeWildcardsAsLiteralCharacters() {
        UUID sellerId = sellerService.createSeller().sellerId();
        // Names chosen so `%` and `_` would match more rows if they were wildcards.
        Product percentLiteral = create(sellerId, "100% cotton shirt");
        Product percentDecoy = create(sellerId, "100 percent cotton shirt");
        Product underscoreLiteral = create(sellerId, "size_42 jacket");
        Product underscoreDecoy = create(sellerId, "sizeX42 jacket");
        create(sellerId, "plain shirt");

        assertThat(products.findListingPage(null, null, "100%", null, null, 10))
                .extracting(Product::getProductId)
                .containsExactly(percentLiteral.getProductId());
        assertThat(products.findListingPage(null, null, "size_42", null, null, 10))
                .extracting(Product::getProductId)
                .containsExactly(underscoreLiteral.getProductId());
        // Matching is still case-insensitive substring search on the lowered name.
        assertThat(products.findListingPage(null, null, "cotton", null, null, 10))
                .extracting(Product::getProductId)
                .containsExactlyInAnyOrder(percentLiteral.getProductId(), percentDecoy.getProductId());
        assertThat(percentDecoy.getProductId()).isNotEqualTo(underscoreLiteral.getProductId());
        assertThat(underscoreDecoy.getProductId()).isNotEqualTo(underscoreLiteral.getProductId());
    }

    @Test
    void publicPriceRangesUseEffectiveSkuPriceBeforeStableCursorPagination() {
        UUID sellerId = sellerService.createSeller().sellerId();
        Product activeSaleA = create(sellerId, "active sale A");
        Product activeSaleB = create(sellerId, "active sale B");
        Product futureSale = create(sellerId, "future sale");
        Product expiredSale = create(sellerId, "expired sale");
        Instant now = Instant.ofEpochSecond(Instant.now().getEpochSecond());
        Instant activeStart = now.minusSeconds(60);
        Instant activeEnd = now.plusSeconds(3600);

        productService.registerSku(sellerId, activeSaleA.getProductId(),
                new ProductRequest.RegisterSellerSku("price-a-" + UUID.randomUUID(), 50_000, 1));
        productService.setSkuSalePrice(sellerId, activeSaleA.getProductId(),
                sellerSkuId(activeSaleA), 20_000, activeStart, activeEnd);
        productService.registerSku(sellerId, activeSaleB.getProductId(),
                new ProductRequest.RegisterSellerSku("price-b-" + UUID.randomUUID(), 60_000, 1));
        productService.setSkuSalePrice(sellerId, activeSaleB.getProductId(),
                sellerSkuId(activeSaleB), 22_000, activeStart, activeEnd);
        productService.registerSku(sellerId, futureSale.getProductId(),
                new ProductRequest.RegisterSellerSku("price-future-" + UUID.randomUUID(), 40_000, 1));
        productService.setSkuSalePrice(sellerId, futureSale.getProductId(),
                sellerSkuId(futureSale), 10_000, now.plusSeconds(3600), now.plusSeconds(7200));
        productService.registerSku(sellerId, expiredSale.getProductId(),
                new ProductRequest.RegisterSellerSku("price-expired-" + UUID.randomUUID(), 50_000, 1));
        productService.setSkuSalePrice(sellerId, expiredSale.getProductId(),
                sellerSkuId(expiredSale), 5_000, now.minusSeconds(7200), now.minusSeconds(3600));

        CatalogResponse.ProductListingPage firstPage = productService.listProductsPage(null, null, null,
                20_000L, 22_000L, 1, null);
        CatalogResponse.ProductListingPage secondPage = productService.listProductsPage(null, null, null,
                20_000L, 22_000L, 1, firstPage.nextCursor());

        assertThat(firstPage.items()).hasSize(1);
        assertThat(firstPage.nextCursor()).isNotNull();
        assertThat(secondPage.items()).hasSize(1);
        assertThat(List.of(firstPage.items().getFirst().product().productId(),
                secondPage.items().getFirst().product().productId()))
                .containsExactlyInAnyOrder(activeSaleA.getProductId(), activeSaleB.getProductId());
        assertThatThrownBy(() -> productService.listProductsPage(null, null, null,
                21_000L, 22_000L, 1, firstPage.nextCursor()))
                .isInstanceOf(CatalogException.class);

        assertThat(productIds(productService.listProductsPage(null, null, null,
                20_000L, 20_000L, 10, null))).containsExactly(activeSaleA.getProductId());
        assertThat(productIds(productService.listProductsPage(null, null, null,
                39_000L, 41_000L, 10, null))).containsExactly(futureSale.getProductId());
        assertThat(productIds(productService.listProductsPage(null, null, null,
                49_000L, 51_000L, 10, null))).containsExactly(expiredSale.getProductId());
        assertThat(products.findListingPage(sellerId, null, "", null, null, 10,
                20_000L, 20_000L, activeStart))
                .extracting(Product::getProductId).containsExactly(activeSaleA.getProductId());
        assertThat(products.findListingPage(sellerId, null, "", null, null, 10,
                20_000L, 20_000L, activeEnd)).isEmpty();
        assertThat(productIds(productService.listProductsPage(sellerId, null, null,
                39_000L, null, 10, null)))
                .containsExactlyInAnyOrder(futureSale.getProductId(), expiredSale.getProductId());
        assertThat(productIds(productService.listProductsPage(sellerId, null, null,
                null, 22_000L, 10, null)))
                .containsExactlyInAnyOrder(activeSaleA.getProductId(), activeSaleB.getProductId());
    }

    @Test
    void repositoryCursorMatchesJavaUuidNaturalOrderAndSearchTreatsWildcardsLiterally() {
        UUID sellerId = sellerService.createSeller().sellerId();
        UUID categoryId = jdbc.queryForObject("SELECT category_id FROM product_category WHERE seller_id=? AND code=?",
                UUID.class, sellerId, "UNCATEGORIZED");
        UUID negativeLeastSignificantBits = UUID.fromString("00000000-0000-0000-8000-000000000000");
        UUID positiveLeastSignificantBits = UUID.fromString("00000000-0000-0000-7fff-ffffffffffff");
        jdbc.update("INSERT INTO product (product_id, seller_id, primary_category_id, name, description) VALUES (?, ?, ?, ?, ?)",
                positiveLeastSignificantBits, sellerId, categoryId, "literal % value", "description");
        jdbc.update("INSERT INTO product (product_id, seller_id, primary_category_id, name, description) VALUES (?, ?, ?, ?, ?)",
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
                new ProductRequest.CreateProduct("atomic registration", null)).productId();
        String successfulSku = "atomic-success-" + UUID.randomUUID();

        productService.registerSku(sellerId, productId, new ProductRequest.RegisterSellerSku(successfulSku, 100, 2));

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
                    new ProductRequest.RegisterSellerSku(failingSku, 100, 2)))
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
                new ProductRequest.CreateProduct("sale-price-" + UUID.randomUUID(), "description")).productId();
        String skuId = "sale-sku-" + UUID.randomUUID();
        productService.registerSku(sellerId, productId, new ProductRequest.RegisterSellerSku(skuId, 1000, 1));
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
                sellerId, new ProductRequest.CreateProduct("orderability-" + UUID.randomUUID(), "description")).productId();
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
                new ProductRequest.CreateProduct("scope-active-1-" + UUID.randomUUID(), "description"));
        productService.createProduct(sellerId,
                new ProductRequest.CreateProduct("scope-active-2-" + UUID.randomUUID(), "description"));
        UUID inactiveProduct = productService.createProduct(sellerId,
                new ProductRequest.CreateProduct("scope-inactive-" + UUID.randomUUID(), "description")).productId();
        mockMvc.perform(put("/api/sellers/{sellerId}/products/{productId}/status", sellerId, inactiveProduct)
                        .contentType("application/json")
                        .content("{\"status\":\"INACTIVE\"}"))
                .andExpect(status().isOk());

        CatalogResponse.ProductListingPage publicPage = objectMapper.readValue(
                mockMvc.perform(get("/api/products").param("sellerId", sellerId.toString()).param("limit", "1"))
                        .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(),
                CatalogResponse.ProductListingPage.class);
        CatalogResponse.ProductListingPage sellerPage = objectMapper.readValue(
                mockMvc.perform(get("/api/sellers/{sellerId}/products", sellerId).param("limit", "1"))
                        .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(),
                CatalogResponse.ProductListingPage.class);
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
        UUID productId = productService.createProduct(sellerId, new ProductRequest.CreateProduct(name, "description"))
                .productId();
        return products.findById(productId).orElseThrow();
    }
    private String sellerSkuId(Product product) {
        return jdbc.queryForObject("SELECT sku_id FROM seller_sku WHERE product_id = ?", String.class,
                product.getProductId());
    }

    private List<UUID> productIds(CatalogResponse.ProductListingPage page) {
        return page.items().stream().map(item -> item.product().productId()).toList();
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
        String relatedCategoryBody = mockMvc.perform(post("/api/sellers/{sellerId}/product-categories", sellerId)
.contentType("application/json").content("{\"code\":\"TEA-RELATED\",\"name\":\"Related\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        UUID relatedCategoryId = UUID.fromString(
                objectMapper.readTree(relatedCategoryBody).get("categoryId").stringValue());
        mockMvc.perform(get("/api/sellers/{sellerId}/product-categories", sellerId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].code").value("TEA"))
                .andExpect(jsonPath("$[1].code").value("TEA-RELATED"))
                .andExpect(jsonPath("$[2].code").value("UNCATEGORIZED"));
        String productBody = mockMvc.perform(post("/api/sellers/{sellerId}/products", sellerId)
                        .contentType("application/json")
                        .content("{\"name\":\"Tea one\",\"description\":\"one\",\"categoryIds\":[\""
                                + categoryId + "\",\"" + relatedCategoryId + "\"]}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        assertThat(objectMapper.readValue(productBody, CatalogResponse.Product.class).categoryIds())
                .containsExactlyElementsOf(List.of(categoryId, relatedCategoryId).stream().sorted().toList());
        UUID productId = UUID.fromString(objectMapper.readTree(productBody).get("productId").stringValue());
        String skuId = "catalog-category-" + UUID.randomUUID();
        productService.registerSku(sellerId, productId, new ProductRequest.RegisterSellerSku(skuId, 100, 3));
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
                        .content("{\"name\":\"Tea two\",\"description\":\"two\",\"categoryIds\":[\""
                                + categoryId + "\"]}"))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/api/sellers/{sellerId}/products", sellerId)
                        .contentType("application/json")
                        .content("{\"name\":\"Tea default\",\"description\":\"default\"}"))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.categoryIds[0]").value(defaultId));
        mockMvc.perform(get("/api/products").param("categoryId", categoryId.toString()))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
        mockMvc.perform(get("/api/sellers/{sellerId}/products", otherSellerId)
                        .param("categoryId", categoryId.toString()))
                .andExpect(status().isNotFound());
        String globalSellerBody = mockMvc.perform(get("/api/products")
                        .param("sellerId", sellerId.toString()).param("categoryId", categoryId.toString()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        CatalogResponse.ProductListingPage globalSellerPage =
                objectMapper.readValue(globalSellerBody, CatalogResponse.ProductListingPage.class);
        assertThat(globalSellerPage.items()).hasSize(2).allSatisfy(item ->
                assertThat(item.product().categoryIds()).contains(categoryId));
        String relatedPageBody = mockMvc.perform(get("/api/sellers/{sellerId}/products", sellerId)
                        .param("categoryId", relatedCategoryId.toString()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        CatalogResponse.ProductListingPage relatedPage =
                objectMapper.readValue(relatedPageBody, CatalogResponse.ProductListingPage.class);
        assertThat(relatedPage.items()).hasSize(1);
        assertThat(relatedPage.items().getFirst().product().productId()).isEqualTo(productId);
        String pageBody = mockMvc.perform(get("/api/sellers/{sellerId}/products", sellerId)
                        .param("categoryId", categoryId.toString()).param("query", "TEA").param("limit", "1"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        CatalogResponse.ProductListingPage page = objectMapper.readValue(pageBody, CatalogResponse.ProductListingPage.class);
        assertThat(page.items()).hasSize(1);
        UUID firstPageProductId = page.items().getFirst().product().productId();
        assertThat(page.items().getFirst().product().categoryIds()).contains(categoryId);
        assertThat(page.nextCursor()).isNotBlank();
        String nextPageBody = mockMvc.perform(get("/api/sellers/{sellerId}/products", sellerId)
                        .param("categoryId", categoryId.toString()).param("query", "TEA").param("limit", "1")
                        .param("cursor", page.nextCursor()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        CatalogResponse.ProductListingPage nextPage = objectMapper.readValue(nextPageBody, CatalogResponse.ProductListingPage.class);
        assertThat(nextPage.items()).hasSize(1);
        UUID secondPageProductId = nextPage.items().getFirst().product().productId();
        assertThat(secondPageProductId).isNotEqualTo(firstPageProductId);
        assertThat(List.of(firstPageProductId, secondPageProductId)).contains(productId);
        assertThat(nextPage.items().getFirst().product().categoryIds()).contains(categoryId);
        assertThat(nextPage.nextCursor()).isNull();
        mockMvc.perform(get("/api/sellers/{sellerId}/products", sellerId)
                        .param("categoryId", defaultId).param("limit", "1").param("cursor", page.nextCursor()))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
        mockMvc.perform(get("/api/sellers/{sellerId}/products", sellerId)
                        .param("categoryId", categoryId.toString()).param("query", "Different").param("limit", "1")
                        .param("cursor", page.nextCursor()))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
        mockMvc.perform(put("/api/sellers/{sellerId}/products/{productId}/categories", sellerId, productId)
                        .contentType("application/json").content("{\"categoryIds\":[\"" + defaultId + "\"]}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.categoryIds[0]").value(defaultId));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM product_category_assignment "
                + "WHERE seller_id=? AND product_id=?", Integer.class, sellerId, productId)).isZero();
        assertThat(jdbc.queryForObject("SELECT primary_category_id FROM product WHERE product_id=?",
                UUID.class, productId)).isEqualTo(UUID.fromString(defaultId));
        mockMvc.perform(put("/api/sellers/{sellerId}/products/{productId}/categories", otherSellerId, productId)
                        .contentType("application/json").content("{\"categoryIds\":[\"" + defaultId + "\"]}"))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/sellers/{sellerId}/product-categories", sellerId))
                .andExpect(status().isOk());
        assertThat(jdbc.queryForMap(inventoryQuery, sellerId, skuId)).isEqualTo(inventoryBefore);
    }


    /**
     * 목록 읽기 모델이 원본과 어긋나지 않는지 고정합니다.
     *
     * <p>투영은 목록 조회 비용을 buffer 1,132 → 103 으로 줄이는 대신 원본과의
     * 시차를 허용합니다. 그 시차가 "영원히 낡음"이 되지 않도록, 카탈로그 쓰기마다
     * 같은 트랜잭션에서 갱신되는지 확인합니다.
     *
     * <p>구현 중 실제로 잡은 버그: {@code @ElementCollection} 은 flush 시점에
     * 반영되므로, flush 전에 투영을 갱신하면 이전 채널 상태가 들어갑니다.
     */
    @Test
    void listingProjectionFollowsCatalogWritesWithinTheSameTransaction() {
        UUID sellerId = sellerService.createSeller().sellerId();
        UUID productId = productService.createProduct(
                sellerId, new ProductRequest.CreateProduct("projection-" + UUID.randomUUID(), "description")).productId();

        // 생성 직후: 기본 채널 두 개가 투영에 들어와 있어야 한다.
        assertThat(projectionChannels(productId))
                .containsExactlyInAnyOrder("ONLINE_STORE", "CAMPAIGN");

        // 채널을 끄면 투영도 즉시 따라와야 한다.
        productService.updateChannelAvailability(sellerId, productId, ProductSalesChannel.ONLINE_STORE, false);
        assertThat(projectionChannels(productId)).containsExactly("CAMPAIGN");

        // 카테고리를 바꾸면 투영의 categories 도 따라와야 한다(최소 1개가 필요하다).
        // 판매자 생성 시 기본 카테고리가 만들어진다. 업데이트에 최소 1개가 필요하다.
        UUID categoryId = jdbc.queryForObject(
                "SELECT category_id FROM product_category WHERE seller_id = ? LIMIT 1", UUID.class, sellerId);
        productService.updateProductCategories(sellerId, productId, List.of(categoryId));
        assertThat(projectionStatus(productId)).isEqualTo("ACTIVE");

        // 상태를 바꾸면 투영 status 가 따라와, 목록에서 사라진다.
        productService.updateStatus(sellerId, productId, ProductStatus.INACTIVE);
        assertThat(projectionStatus(productId)).isEqualTo("INACTIVE");
    }

    private List<String> projectionChannels(UUID productId) {
        return jdbc.queryForObject(
                "SELECT channels FROM product_listing_projection WHERE product_id = ?",
                (rs, row) -> {
                    Array array = rs.getArray("channels");
                    try {
                        return array == null ? List.<String>of() : List.of((String[]) array.getArray());
                    } catch (SQLException exception) {
                        throw new IllegalStateException(exception);
                    }
                }, productId);
    }

    private String projectionStatus(UUID productId) {
        return jdbc.queryForObject(
                "SELECT status FROM product_listing_projection WHERE product_id = ?", String.class, productId);
    }
}
