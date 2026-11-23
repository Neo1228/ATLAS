package com.atlas.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.atlas.application.dto.response.ProductReviewResponse;
import com.atlas.application.exception.ApplicationException.Code;
import com.atlas.application.exception.ApplicationException;
import com.atlas.application.repository.ProductReviewRepository;
import com.atlas.application.service.ProductReviewService;
import com.atlas.catalog.exception.CatalogException;
import com.atlas.catalog.service.ProductService;
import com.atlas.order.service.OrderService;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.util.StreamUtils;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@Testcontainers(disabledWithoutDocker = true)
class BuyerProductReviewIntegrationTest {
    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18.6");

    private JdbcTemplate jdbc;
    private ProductReviewRepository repository;
    private ProductService products;
    private OrderService orders;
    private ProductReviewService reviews;

    @BeforeEach
    void setUp() throws IOException {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("DROP SCHEMA public CASCADE");
        jdbc.execute("CREATE SCHEMA public");
        jdbc.execute("CREATE TABLE product (product_id UUID PRIMARY KEY)");
        jdbc.execute("CREATE TABLE order_item (order_item_id UUID PRIMARY KEY)");
        ClassPathResource migration = new ClassPathResource("db/migration/V36__verified_buyer_product_reviews.sql");
        try (var input = migration.getInputStream()) {
            jdbc.execute(StreamUtils.copyToString(input, StandardCharsets.UTF_8));
        }
        repository = new ProductReviewRepository(jdbc);
        products = mock(ProductService.class);
        when(products.isPublicOrderable(any())).thenReturn(true);
        orders = mock(OrderService.class);
        reviews = new ProductReviewService(repository, products, orders);
    }

    @Test
    void onlyAnOrderServiceVerifiedDeliveredPurchaseCanCreateAReview() {
        UUID productId = UUID.randomUUID();
        UUID orderItemId = UUID.randomUUID();
        jdbc.update("INSERT INTO product (product_id) VALUES (?)", productId);
        jdbc.update("INSERT INTO order_item (order_item_id) VALUES (?)", orderItemId);
        when(orders.findDeliveredOrderItemId("buyer-1", productId)).thenReturn(Optional.of(orderItemId));

        ProductReviewResponse created = reviews.create("buyer-1", productId, 5, "Arrived as described");

        assertThat(created.rating()).isEqualTo(5);
        assertThat(created.body()).isEqualTo("Arrived as described");
        assertThat(jdbc.queryForObject("SELECT order_item_id FROM product_review WHERE review_id = ?",
                UUID.class, created.reviewId())).isEqualTo(orderItemId);
        assertThat(jdbc.queryForObject("SELECT buyer_ref FROM product_review WHERE review_id = ?",
                String.class, created.reviewId())).isEqualTo("buyer-1");
    }

    @Test
    void rejectsUnverifiedPurchaseAndDuplicateBuyerProductReview() {
        UUID productId = UUID.randomUUID();
        UUID orderItemId = UUID.randomUUID();
        jdbc.update("INSERT INTO product (product_id) VALUES (?)", productId);
        jdbc.update("INSERT INTO order_item (order_item_id) VALUES (?)", orderItemId);
        when(orders.findDeliveredOrderItemId("buyer-1", productId)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> reviews.create("buyer-1", productId, 4, "Review"))
                .isInstanceOf(ApplicationException.class);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM product_review", Integer.class)).isZero();

        when(orders.findDeliveredOrderItemId("buyer-1", productId)).thenReturn(Optional.of(orderItemId));
        reviews.create("buyer-1", productId, 4, "Review");
        assertThatThrownBy(() -> reviews.create("buyer-1", productId, 5, "Another review"))
                .isInstanceOf(ApplicationException.class);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM product_review", Integer.class)).isEqualTo(1);
    }

    @Test
    void publicPageAggregatesRatingsAndPaginatesNewestReviews() {
        UUID productId = UUID.randomUUID();
        UUID firstOrderItemId = UUID.randomUUID();
        UUID secondOrderItemId = UUID.randomUUID();
        jdbc.update("INSERT INTO product (product_id) VALUES (?)", productId);
        jdbc.update("INSERT INTO order_item (order_item_id) VALUES (?)", firstOrderItemId);
        jdbc.update("INSERT INTO order_item (order_item_id) VALUES (?)", secondOrderItemId);
        when(orders.findDeliveredOrderItemId("buyer-a", productId)).thenReturn(Optional.of(firstOrderItemId));
        when(orders.findDeliveredOrderItemId("buyer-b", productId)).thenReturn(Optional.of(secondOrderItemId));
        reviews.create("buyer-a", productId, 5, "Great");
        reviews.create("buyer-b", productId, 3, "Good");

        var page = reviews.list(productId, 1, 0);

        assertThat(page.averageRating()).isEqualByComparingTo(new BigDecimal("4.0"));
        assertThat(page.reviewCount()).isEqualTo(2);
        assertThat(page.items()).hasSize(1);
        assertThat(page.hasMore()).isTrue();
        assertThat(reviews.list(productId, 1, 1).items()).hasSize(1);
        assertThat(reviews.list(productId, 1, 1).hasMore()).isFalse();
    }

    @Test
    void postgresEnforcesRatingBodyAndPurchaseForeignKeys() {
        UUID productId = UUID.randomUUID();
        UUID orderItemId = UUID.randomUUID();
        jdbc.update("INSERT INTO product (product_id) VALUES (?)", productId);
        jdbc.update("INSERT INTO order_item (order_item_id) VALUES (?)", orderItemId);

        when(orders.findDeliveredOrderItemId("buyer-3", productId)).thenReturn(Optional.of(orderItemId));
        assertThatThrownBy(() -> reviews.create("buyer-3", productId, 0, "body"))
                .isInstanceOf(ApplicationException.class);
        assertThatThrownBy(() -> reviews.create("buyer-3", productId, 5, "   "))
                .isInstanceOf(ApplicationException.class);
        assertThatThrownBy(() -> reviews.create("buyer-3", productId, 5, "x".repeat(2001)))
                .isInstanceOf(ApplicationException.class);

        assertThatThrownBy(() -> repository.create("buyer-1", productId, orderItemId, 6, "bad rating"))
                .isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> repository.create("buyer-1", productId, orderItemId, 4, "   "))
                .isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> repository.create("buyer-2", UUID.randomUUID(), UUID.randomUUID(), 4, "valid text"))
                .isInstanceOf(DataAccessException.class);
    }

    @Test
    void listingReviewsOfAnUnorderableProductUsesTheCatalogNotFoundContract() {
        UUID productId = UUID.randomUUID();
        when(products.isPublicOrderable(productId)).thenReturn(false);

        assertThatThrownBy(() -> reviews.list(productId, 0, 0))
                .isInstanceOfSatisfying(CatalogException.class,
                        exception -> assertThat(exception.code()).isEqualTo(CatalogException.Code.NOT_FOUND));
    }
}
