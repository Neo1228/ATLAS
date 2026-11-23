package com.atlas.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.atlas.application.dto.response.BuyerWishlistItemResponse;
import com.atlas.application.exception.ApplicationException;
import com.atlas.application.repository.BuyerWishlistRepository;
import com.atlas.application.service.BuyerWishlistService;
import com.atlas.catalog.service.ProductService;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
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
class BuyerWishlistIntegrationTest {
    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18.6");

    private JdbcTemplate jdbc;
    private BuyerWishlistRepository repository;
    private ProductService products;
    private BuyerWishlistService wishlist;

    @BeforeEach
    void setUp() throws IOException {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("DROP SCHEMA public CASCADE");
        jdbc.execute("CREATE SCHEMA public");
        jdbc.execute("CREATE TABLE product (product_id UUID PRIMARY KEY)");
        ClassPathResource migration = new ClassPathResource("db/migration/V35__buyer_product_wishlist.sql");
        try (var input = migration.getInputStream()) {
            jdbc.execute(StreamUtils.copyToString(input, StandardCharsets.UTF_8));
        }
        repository = new BuyerWishlistRepository(jdbc);
        products = mock(ProductService.class);
        when(products.isPublicOrderable(any())).thenReturn(true);
        wishlist = new BuyerWishlistService(repository, products);
    }

    @Test
    void savingTheSameProductIsIdempotentAndRemovalIsRepeatable() {
        String buyerRef = "buyer-1";
        UUID productId = UUID.randomUUID();
        jdbc.update("INSERT INTO product (product_id) VALUES (?)", productId);

        BuyerWishlistItemResponse first = wishlist.save(buyerRef, productId);
        BuyerWishlistItemResponse replay = wishlist.save(buyerRef, productId);

        assertThat(replay).isEqualTo(first);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM buyer_wishlist_item", Integer.class)).isEqualTo(1);
        verify(products, times(2)).isPublicOrderable(productId);
        wishlist.remove(buyerRef, productId);
        wishlist.remove(buyerRef, productId);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM buyer_wishlist_item", Integer.class)).isZero();
    }

    @Test
    void listUsesStableSavedTimeAndProductIdOrderingWithBoundedHasMore() {
        String buyerRef = "buyer-2";
        UUID firstId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        UUID secondId = UUID.fromString("00000000-0000-0000-0000-000000000002");
        UUID thirdId = UUID.fromString("00000000-0000-0000-0000-000000000003");
        for (UUID productId : List.of(firstId, secondId, thirdId)) {
            jdbc.update("INSERT INTO product (product_id) VALUES (?)", productId);
            repository.saveIfAbsent(buyerRef, productId);
        }
        Instant earlier = Instant.parse("2026-01-01T00:00:00Z");
        Instant later = Instant.parse("2026-01-02T00:00:00Z");
        jdbc.update("UPDATE buyer_wishlist_item SET saved_at = ? WHERE product_id = ?", Timestamp.from(earlier), firstId);
        jdbc.update("UPDATE buyer_wishlist_item SET saved_at = ? WHERE product_id = ?", Timestamp.from(later), secondId);
        jdbc.update("UPDATE buyer_wishlist_item SET saved_at = ? WHERE product_id = ?", Timestamp.from(later), thirdId);

        var page = wishlist.list(buyerRef, 2, 0);

        assertThat(page.items()).extracting(BuyerWishlistItemResponse::productId).containsExactly(thirdId, secondId);
        assertThat(page.hasMore()).isTrue();
        assertThat(wishlist.list(buyerRef, 2, 2).items())
                .extracting(BuyerWishlistItemResponse::productId).containsExactly(firstId);
        assertThat(wishlist.list(buyerRef, 2, 2).hasMore()).isFalse();
    }

    @Test
    void repositoryRejectsMissingProductAndBlankBuyerReference() {
        assertThatThrownBy(() -> repository.saveIfAbsent("buyer-3", UUID.randomUUID()))
                .isInstanceOf(DataAccessException.class);

        UUID productId = UUID.randomUUID();
        jdbc.update("INSERT INTO product (product_id) VALUES (?)", productId);
        assertThatThrownBy(() -> repository.saveIfAbsent("   ", productId))
                .isInstanceOf(DataAccessException.class);
    }

    @Test
    void listRejectsUnboundedPageRequests() {
        assertThatThrownBy(() -> wishlist.list("buyer-4", 101, 0))
                .isInstanceOf(ApplicationException.class);
        assertThatThrownBy(() -> wishlist.list("buyer-4", 20, 10_001))
                .isInstanceOf(ApplicationException.class);
    }
}
