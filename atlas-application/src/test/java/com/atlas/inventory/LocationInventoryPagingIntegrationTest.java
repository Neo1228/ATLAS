package com.atlas.inventory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.atlas.inventory.dto.response.InventoryResponse;
import com.atlas.catalog.dto.request.ProductRequest;
import com.atlas.catalog.service.ProductService;
import com.atlas.catalog.service.SellerService;
import com.atlas.inventory.exception.InventoryException;
import com.atlas.inventory.service.InventoryService;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/** 위치별 재고 목록이 SKU 수와 무관하게 한 페이지 크기로 제한되고, 페이지를 따라가면 모든 SKU를 한 번씩 반환한다. */
@Testcontainers
@SpringBootTest
class LocationInventoryPagingIntegrationTest {
    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18.6");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("atlas.admission.expiry-poll-interval", () -> "PT24H");
        registry.add("atlas.orders.expiry-poll-interval", () -> "PT24H");
    }

    @Autowired SellerService sellers;
    @Autowired ProductService products;
    @Autowired InventoryService inventory;
    @Autowired JdbcTemplate jdbc;

    @Test
    void followingTheCursorReturnsEverySkuExactlyOnceInOrder() {
        UUID sellerId = sellers.createSeller().sellerId();
        var product = products.createProduct(sellerId, new ProductRequest.CreateProduct("Paged", "paging fixture"));
        List<String> registered = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            String sku = "paging-sku-%02d".formatted(i);
            products.registerSku(sellerId, product.productId(), new ProductRequest.RegisterSellerSku(sku, 100, 2));
            registered.add(sku);
        }
        UUID locationId = jdbc.queryForObject(
                "SELECT location_id FROM inventory_location WHERE seller_id = ? AND location_code = 'DEFAULT'",
                UUID.class, sellerId);

        List<String> seen = new ArrayList<>();
        String cursor = null;
        int pages = 0;
        do {
            InventoryResponse.LocationInventoryPage page = inventory.findLocationInventoryPage(sellerId, locationId, 2, cursor);
            assertThat(page.items()).hasSizeLessThanOrEqualTo(2);
            page.items().forEach(item -> seen.add(item.skuId()));
            cursor = page.nextCursor();
            pages++;
        } while (cursor != null);

        assertThat(seen).containsExactlyElementsOf(registered);
        assertThat(pages).isEqualTo(3);
    }

    @Test
    void pageSizeOutsideTheAllowedRangeIsRejected() {
        UUID sellerId = sellers.createSeller().sellerId();

        assertThatThrownBy(() -> inventory.findLocationInventoryPage(sellerId, UUID.randomUUID(), 0, null))
                .isInstanceOf(InventoryException.class);
        assertThatThrownBy(() -> inventory.findLocationInventoryPage(sellerId, UUID.randomUUID(), 501, null))
                .isInstanceOf(InventoryException.class);
    }
}
