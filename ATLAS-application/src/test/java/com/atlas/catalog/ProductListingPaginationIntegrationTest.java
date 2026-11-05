package com.atlas.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.atlas.catalog.dto.request.CreateProductRequest;
import com.atlas.catalog.dto.request.RegisterSellerSkuRequest;
import com.atlas.catalog.persistence.entity.Product;
import com.atlas.catalog.repository.ProductRepository;
import com.atlas.catalog.service.ProductService;
import com.atlas.catalog.service.SellerService;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;
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
class ProductListingPaginationIntegrationTest {
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

    @Test
    void repositoryPagesAreBoundedAndContinueInD067OrderWithFilters() throws Exception {
        UUID firstSeller = sellerService.createSeller().sellerId();
        UUID secondSeller = sellerService.createSeller().sellerId();
        List<Product> seeded = new ArrayList<>();
        seeded.add(create(firstSeller, "  Needle one  "));
        seeded.add(create(firstSeller, "Needle two"));
        seeded.add(create(firstSeller, "Needle three"));
        seeded.add(create(secondSeller, "Needle four"));
        create(secondSeller, "Other product");

        List<Product> expected = seeded.stream()
                .sorted(Comparator.comparing(Product::getSellerId).thenComparing(Product::getProductId)).toList();
        List<Product> firstPage = products.findListingPage(null, "needle", null, null, 3);
        assertThat(firstPage).hasSize(2);
        Product lastFirstPage = firstPage.getLast();
        List<Product> secondPage = products.findListingPage(null, "needle", lastFirstPage.getSellerId(),
                lastFirstPage.getProductId(), 3);
        assertThat(secondPage).hasSize(2);
        Product lastSecondPage = secondPage.getLast();
        List<Product> thirdPage = products.findListingPage(null, "needle", lastSecondPage.getSellerId(),
                lastSecondPage.getProductId(), 3);

        assertThat(Stream.of(firstPage, secondPage, thirdPage).flatMap(List::stream).toList())
                .containsExactlyElementsOf(expected);
        assertThat(products.findListingPage(firstSeller, "needle", null, null, 10))
                .hasSize(3)
                .allSatisfy(product -> assertThat(product.getSellerId()).isEqualTo(firstSeller));
        productService.registerSku(expected.get(0).getSellerId(), expected.get(0).getProductId(),
                new RegisterSellerSkuRequest("CURRENT-PAGE-SKU", 100, 1));
        productService.registerSku(expected.get(2).getSellerId(), expected.get(2).getProductId(),
                new RegisterSellerSkuRequest("LOOKAHEAD-SKU", 100, 1));
        var servicePage = productService.listProductsPage(null, "needle", null, null, 2);
        assertThat(servicePage).hasSize(3);
        assertThat(servicePage.getFirst().variants()).extracting(variant -> variant.skuId())
                .containsExactly("CURRENT-PAGE-SKU");
        assertThat(servicePage.get(2).variants()).isEmpty();
        long countBeforeGet = products.count();
        mockMvc.perform(get("/api/products").param("query", " NEEDLE ").param("limit", "2"))
                .andExpect(status().isOk());
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

    private Product create(UUID sellerId, String name) {
        UUID productId = productService.createProduct(sellerId, new CreateProductRequest(name, "description"))
                .productId();
        return products.findById(productId).orElseThrow();
    }
}
