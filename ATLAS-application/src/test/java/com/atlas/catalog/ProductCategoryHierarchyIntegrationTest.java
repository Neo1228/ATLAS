package com.atlas.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.atlas.catalog.dto.request.CreateProductCategoryRequest;
import com.atlas.catalog.dto.request.CreateProductRequest;
import com.atlas.catalog.dto.request.UpdateProductCategoryParentRequest;
import com.atlas.catalog.dto.response.ProductListingPageResponse;
import com.atlas.catalog.exception.CatalogException;
import com.atlas.catalog.exception.CatalogException.Code;
import com.atlas.catalog.service.ProductCategoryService;
import com.atlas.catalog.service.ProductService;
import com.atlas.catalog.service.ProductStatus;
import com.atlas.catalog.service.SellerService;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class ProductCategoryHierarchyIntegrationTest {
    @Container
    static final PostgreSQLContainer POSTGRES =
            new PostgreSQLContainer("postgres:18.6");

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry properties) {
        properties.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        properties.add("spring.datasource.username", POSTGRES::getUsername);
        properties.add("spring.datasource.password", POSTGRES::getPassword);
        properties.add("atlas.admission.expiry-poll-interval", () -> "PT24H");
        properties.add("atlas.orders.expiry-poll-interval", () -> "PT24H");
    }

    @Autowired ProductCategoryService categories;
    @Autowired ProductService products;
    @Autowired SellerService sellers;
    @Autowired JdbcTemplate jdbc;
    @Autowired TransactionTemplate transactions;
    @Autowired MockMvc mockMvc;

    @Test
    void sameSellerTreeFilteringVisibilityOrderAndCursorSemantics() throws Exception {
        UUID seller = sellers.createSeller().sellerId();
        UUID otherSeller = sellers.createSeller().sellerId();
        var parent = categories.createCategory(seller, new CreateProductCategoryRequest("PARENT", "Parent"));
        var child = categories.createCategory(seller, new CreateProductCategoryRequest("CHILD", "Child"));
        var grandchild = categories.createCategory(seller, new CreateProductCategoryRequest("GRAND", "Grand"));
        var outside = categories.createCategory(seller, new CreateProductCategoryRequest("OUTSIDE", "Outside"));
        var foreign = categories.createCategory(otherSeller, new CreateProductCategoryRequest("FOREIGN", "Foreign"));
        categories.updateCategoryParent(seller, child.categoryId(), new UpdateProductCategoryParentRequest(parent.categoryId()));
        categories.updateCategoryParent(seller, grandchild.categoryId(), new UpdateProductCategoryParentRequest(child.categoryId()));

        String categoryUrl = "/api/sellers/" + seller + "/product-categories/" + child.categoryId() + "/parent";
        mockMvc.perform(put(categoryUrl).contentType("application/json").content("{}"))
                .andExpect(status().isBadRequest());
        assertThat(categories.listCategories(seller).stream().filter(c -> c.categoryId().equals(child.categoryId()))
                .findFirst().orElseThrow().parentCategoryId()).isEqualTo(parent.categoryId());
        mockMvc.perform(delete(categoryUrl)).andExpect(status().isOk());
        assertThat(categories.listCategories(seller).stream().filter(c -> c.categoryId().equals(child.categoryId()))
                .findFirst().orElseThrow().parentCategoryId()).isNull();
        categories.updateCategoryParent(seller, child.categoryId(), new UpdateProductCategoryParentRequest(parent.categoryId()));

        assertCatalogFailure(() -> categories.updateCategoryParent(seller, parent.categoryId(),
                new UpdateProductCategoryParentRequest(foreign.categoryId())), Code.NOT_FOUND);
        assertCatalogFailure(() -> categories.updateCategoryParent(seller, parent.categoryId(),
                new UpdateProductCategoryParentRequest(parent.categoryId())), Code.CONFLICT);
        assertCatalogFailure(() -> categories.updateCategoryParent(seller, parent.categoryId(),
                new UpdateProductCategoryParentRequest(grandchild.categoryId())), Code.CONFLICT);
        UUID uncategorized = jdbc.queryForObject(
                "SELECT category_id FROM product_category WHERE seller_id = ? AND code = 'UNCATEGORIZED'",
                UUID.class, seller);
        assertCatalogFailure(() -> categories.updateCategoryParent(seller, uncategorized,
                new UpdateProductCategoryParentRequest(parent.categoryId())), Code.CONFLICT);

        var expected = new ArrayList<UUID>();
        expected.add(createProduct(seller, parent.categoryId()));
        expected.add(createProduct(seller, child.categoryId()));
        expected.add(createProduct(seller, grandchild.categoryId()));
        createProduct(seller, outside.categoryId());
        List<ProductListingPageResponse> pages = new ArrayList<>();
        ProductListingPageResponse page = products.listSellerProductsPage(seller, parent.categoryId(), null, 2, null);
        pages.add(page);
        assertThat(page.items()).hasSize(2);
        assertThat(page.nextCursor()).isNotBlank();
        ProductListingPageResponse second = products.listSellerProductsPage(seller, parent.categoryId(), null, 2, page.nextCursor());
        pages.add(second);
        assertThat(second.items()).hasSize(1);
        var actual = new ArrayList<UUID>();
        pages.forEach(p -> p.items().forEach(item -> actual.add(item.product().productId())));
        expected.sort((a,b) -> compareUuid(seller, a, b));
        assertThat(actual).containsExactlyElementsOf(expected);

        UUID inactive = createProduct(seller, grandchild.categoryId());
        products.updateStatus(seller, inactive, ProductStatus.INACTIVE);
        assertThat(products.listProductsPage(seller, parent.categoryId(), null, 10, null).items())
                .extracting(item -> item.product().productId()).doesNotContain(inactive);
        assertThat(products.listSellerProductsPage(seller, parent.categoryId(), null, 10, null).items())
                .extracting(item -> item.product().productId()).contains(inactive);

        assertThat(products.listProductsPage(seller, null, null, 1, legacy("v1", seller, null)).items()).isNotNull();
        assertThat(products.listProductsPage(seller, null, null, 1, legacy("v2", seller, null)).items()).isNotNull();
        assertThat(products.listSellerProductsPage(seller, null, null, 1, legacy("v3", seller, null)).items()).isNotNull();
        assertCatalogFailure(() -> products.listProductsPage(seller, parent.categoryId(), null, 1,
                legacy("v3", seller, parent.categoryId())), Code.INVALID_REQUEST);
    }

    @Test
    void concurrentOpposingMovesSerializeOnSellerAndCannotCommitCycle() throws Exception {
        UUID seller = sellers.createSeller().sellerId();
        var a = categories.createCategory(seller, new CreateProductCategoryRequest("A", "A"));
        var b = categories.createCategory(seller, new CreateProductCategoryRequest("B", "B"));
        CountDownLatch start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var first = pool.submit(() -> moveWhenReady(start, seller, a.categoryId(), b.categoryId()));
            var second = pool.submit(() -> moveWhenReady(start, seller, b.categoryId(), a.categoryId()));
            start.countDown();
            MoveResult firstResult = first.get(20, TimeUnit.SECONDS);
            MoveResult secondResult = second.get(20, TimeUnit.SECONDS);
            assertThat((firstResult.committed() ? 1 : 0) + (secondResult.committed() ? 1 : 0)).isEqualTo(1);
            MoveResult loser = firstResult.committed() ? secondResult : firstResult;
            assertThat(loser.failure()).isNotNull();
            assertThat(loser.failure().code()).isEqualTo(Code.CONFLICT);
            assertThat(jdbc.queryForObject("WITH RECURSIVE walk(id, path, cycle) AS ("
                    + "SELECT category_id, ARRAY[category_id], false FROM product_category WHERE seller_id = ? "
                    + "UNION ALL SELECT c.category_id, path || c.category_id, c.category_id = ANY(path) "
                    + "FROM walk w JOIN product_category c ON c.parent_category_id = w.id WHERE NOT cycle) "
                    + "SELECT COUNT(*) FROM walk WHERE cycle", Integer.class, seller)).isZero();
        }
    }

    private MoveResult moveWhenReady(CountDownLatch start, UUID seller, UUID category, UUID parent) {
        try {
            start.await();
            transactions.executeWithoutResult(status -> categories.updateCategoryParent(seller, category,
                    new UpdateProductCategoryParentRequest(parent)));
            return new MoveResult(true, null);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting to move category", interrupted);
        } catch (CatalogException failure) {
            return new MoveResult(false, failure);
        }
    }

    private record MoveResult(boolean committed, CatalogException failure) { }

    private static void assertCatalogFailure(Runnable action, Code expectedCode) {
        assertThatThrownBy(action::run)
                .isInstanceOfSatisfying(CatalogException.class,
                        failure -> assertThat(failure.code()).isEqualTo(expectedCode));
    }

    private UUID createProduct(UUID seller, UUID category) {
        var response = products.createProduct(seller, new CreateProductRequest("tree-" + UUID.randomUUID(), ""));
        products.updateProductCategory(seller, response.productId(), category);
        return response.productId();
    }

    private static String legacy(String version, UUID seller, UUID category) {
        String encodedKeyword = Base64.getUrlEncoder().withoutPadding().encodeToString(new byte[0]);
        String text = switch (version) {
            case "v1" -> "v1:" + seller + ":" + encodedKeyword + ":" + seller + ":" + seller;
            case "v2" -> "v2:" + seller + ":-:" + encodedKeyword + ":" + seller + ":" + seller;
            default -> "v3:true:" + seller + ":" + (category == null ? "-" : category) + ":"
                    + encodedKeyword + ":" + seller + ":" + seller;
        };
        return Base64.getUrlEncoder().withoutPadding().encodeToString(text.getBytes(StandardCharsets.UTF_8));
    }

    private int compareUuid(UUID seller, UUID first, UUID second) {
        String keyA = jdbc.queryForObject("SELECT encode(set_byte(set_byte(uuid_send(?),0,get_byte(uuid_send(?),0) # 128),8,get_byte(uuid_send(?),8) # 128),'hex')",
                String.class, first, first, first);
        String keyB = jdbc.queryForObject("SELECT encode(set_byte(set_byte(uuid_send(?),0,get_byte(uuid_send(?),0) # 128),8,get_byte(uuid_send(?),8) # 128),'hex')",
                String.class, second, second, second);
        return keyA.compareTo(keyB);
    }
}
