package com.atlas.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
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
        List<Product> firstPage = products.findListingPage(null, "needle", null, null, 2);
        assertThat(firstPage).hasSize(2);
        Product lastFirstPage = firstPage.getLast();
        List<Product> secondPage = products.findListingPage(null, "needle", lastFirstPage.getSellerId(),
                lastFirstPage.getProductId(), 2);
        assertThat(secondPage).hasSize(2);
        Product lastSecondPage = secondPage.getLast();
        List<Product> thirdPage = products.findListingPage(null, "needle", lastSecondPage.getSellerId(),
                lastSecondPage.getProductId(), 2);
        List<UUID> expectedIds = expected.stream().map(Product::getProductId).toList();
        assertThat(firstPage).extracting(Product::getProductId).containsExactlyElementsOf(expectedIds.subList(0, 2));
        assertThat(secondPage).extracting(Product::getProductId).containsExactlyElementsOf(expectedIds.subList(2, 4));
        assertThat(thirdPage).isEmpty();
        assertThat(products.findListingPage(firstSeller, "needle", null, null, 10))
                .hasSize(3).allSatisfy(product -> assertThat(product.getSellerId()).isEqualTo(firstSeller));
        productService.registerSku(expected.get(0).getSellerId(), expected.get(0).getProductId(),
                new RegisterSellerSkuRequest("CURRENT-PAGE-SKU", 100, 1));
        productService.registerSku(expected.get(2).getSellerId(), expected.get(2).getProductId(),
                new RegisterSellerSkuRequest("LOOKAHEAD-SKU", 100, 1));
        var servicePage = productService.listProductsPage(null, " NEEDLE ", 2, null);
        assertThat(servicePage.items()).hasSize(2);
        assertThat(servicePage.nextCursor()).isNotBlank();
        assertThat(servicePage.items().getFirst().variants()).extracting(variant -> variant.skuId())
                .containsExactly("CURRENT-PAGE-SKU");
        assertThat(servicePage.items().get(1).variants()).isEmpty();
        var serviceNextPage = productService.listProductsPage(null, " NEEDLE ", 2, servicePage.nextCursor());
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
                .andExpect(jsonPath("$.detail").value("Invalid product cursor"));
        assertThat(products.count()).isEqualTo(countBeforeGet);
    }

    @Test
    void repositoryCursorMatchesJavaUuidNaturalOrderAndSearchTreatsWildcardsLiterally() {
        UUID sellerId = sellerService.createSeller().sellerId();
        UUID negativeLeastSignificantBits = UUID.fromString("00000000-0000-0000-8000-000000000000");
        UUID positiveLeastSignificantBits = UUID.fromString("00000000-0000-0000-7fff-ffffffffffff");
        jdbc.update("INSERT INTO product (product_id, seller_id, name, description) VALUES (?, ?, ?, ?)",
                positiveLeastSignificantBits, sellerId, "literal % value", "description");
        jdbc.update("INSERT INTO product (product_id, seller_id, name, description) VALUES (?, ?, ?, ?)",
                negativeLeastSignificantBits, sellerId, "literal wildcard", "description");
        List<Product> firstPage = products.findListingPage(sellerId, "", null, null, 1);
        assertThat(firstPage).extracting(Product::getProductId).containsExactly(negativeLeastSignificantBits);
        List<Product> secondPage = products.findListingPage(sellerId, "", sellerId,
                negativeLeastSignificantBits, 1);
        assertThat(secondPage).extracting(Product::getProductId).containsExactly(positiveLeastSignificantBits);
        assertThat(products.findListingPage(sellerId, "%", null, null, 10))
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

    private Product create(UUID sellerId, String name) {
        UUID productId = productService.createProduct(sellerId, new CreateProductRequest(name, "description"))
                .productId();
        return products.findById(productId).orElseThrow();
    }
}
