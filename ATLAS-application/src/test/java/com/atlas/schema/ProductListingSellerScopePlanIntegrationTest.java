package com.atlas.schema;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * V40 global sort index가 seller-scoped listing을 ordered-but-slow 경로로 끌어가지 않는지 검증한다.
 *
 * <p>seller scope에서는 seller sort key가 상수이므로 ORDER BY에서 제외해도 순서가 같다. 그 결과
 * 기존 product_seller_idx 기반 access path가 유지되어야 한다.
 */
@Testcontainers(disabledWithoutDocker = true)
class ProductListingSellerScopePlanIntegrationTest {
    private static final String SELLER_ORDER_BY = """
            ORDER BY CASE WHEN CAST(? AS uuid) IS NOT NULL THEN NULL ELSE p.seller_sort_key END,
                     p.product_sort_key
            """;

    @Container
    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18.6");

    private JdbcTemplate jdbc;

    @BeforeEach
    void setUp() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("DROP SCHEMA public CASCADE");
        jdbc.execute("CREATE SCHEMA public");
        jdbc.execute("""
                CREATE TABLE product (
                    product_id UUID PRIMARY KEY,
                    seller_id UUID NOT NULL,
                    name TEXT NOT NULL,
                    description TEXT,
                    status VARCHAR(16) NOT NULL,
                    primary_category_id UUID
                )
                """);
        jdbc.execute("CREATE INDEX product_seller_idx ON product (seller_id, product_id)");
        jdbc.update("""
                INSERT INTO product (product_id, seller_id, name, description, status)
                SELECT gen_random_uuid(), md5((g % 100)::text)::uuid, 'Product ' || g, 'description', 'ACTIVE'
                FROM generate_series(1, 20000) AS g
                """);
        jdbc.execute(BaselineSchema.createIndex("product_listing_sort_idx"));
        jdbc.execute("ANALYZE product");
    }

    @Test
    void sellerScopedListingDoesNotUseTheGlobalSortIndex() {
        UUID sellerId = jdbc.queryForObject(
                "SELECT seller_id FROM product LIMIT 1", UUID.class);

        List<String> plan = jdbc.queryForList("EXPLAIN (COSTS OFF) "
                + listingSql("?"), String.class, sellerId, sellerId);

        String text = String.join("\n", plan);
        assertThat(text)
                .contains("product_seller_idx")
                .doesNotContain("product_listing_sort_idx", "Seq Scan on product");
    }

    @Test
    void conditionalOrderByPreservesTheEstablishedSellerOrdering() {
        UUID sellerId = jdbc.queryForObject("SELECT seller_id FROM product LIMIT 1", UUID.class);

        List<UUID> conditional = jdbc.queryForList("""
                SELECT product_id FROM product p
                WHERE p.seller_id = ? AND p.status = 'ACTIVE'
                ORDER BY CASE WHEN CAST(? AS uuid) IS NOT NULL THEN NULL ELSE
                    encode(set_byte(set_byte(uuid_send(p.seller_id), 0,
                        get_byte(uuid_send(p.seller_id), 0) # 128), 8,
                        get_byte(uuid_send(p.seller_id), 8) # 128), 'hex') COLLATE "C" END,
                    encode(set_byte(set_byte(uuid_send(p.product_id), 0,
                        get_byte(uuid_send(p.product_id), 0) # 128), 8,
                        get_byte(uuid_send(p.product_id), 8) # 128), 'hex') COLLATE "C"
                """, UUID.class, sellerId, sellerId);
        List<UUID> original = jdbc.queryForList("""
                SELECT product_id FROM product p
                WHERE p.seller_id = ? AND p.status = 'ACTIVE'
                ORDER BY encode(set_byte(set_byte(uuid_send(p.seller_id), 0,
                        get_byte(uuid_send(p.seller_id), 0) # 128), 8,
                        get_byte(uuid_send(p.seller_id), 8) # 128), 'hex') COLLATE "C",
                    encode(set_byte(set_byte(uuid_send(p.product_id), 0,
                        get_byte(uuid_send(p.product_id), 0) # 128), 8,
                        get_byte(uuid_send(p.product_id), 8) # 128), 'hex') COLLATE "C"
                """, UUID.class, sellerId);

        assertThat(conditional).isEqualTo(original);
    }

    private static String listingSql(String sellerPredicate) {
        return """
                SELECT p.product_id FROM (
                    SELECT p.*,
                        encode(set_byte(set_byte(uuid_send(p.seller_id), 0,
                            get_byte(uuid_send(p.seller_id), 0) # 128), 8,
                            get_byte(uuid_send(p.seller_id), 8) # 128), 'hex') COLLATE "C" AS seller_sort_key,
                        encode(set_byte(set_byte(uuid_send(p.product_id), 0,
                            get_byte(uuid_send(p.product_id), 0) # 128), 8,
                            get_byte(uuid_send(p.product_id), 8) # 128), 'hex') COLLATE "C" AS product_sort_key
                    FROM product p
                ) p
                WHERE p.seller_id = %s AND p.status = 'ACTIVE'
                %s
                LIMIT 101
                """.formatted(sellerPredicate, SELLER_ORDER_BY);
    }
}
