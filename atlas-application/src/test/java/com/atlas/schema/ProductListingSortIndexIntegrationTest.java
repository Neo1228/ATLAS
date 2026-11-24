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

@Testcontainers(disabledWithoutDocker = true)
class ProductListingSortIndexIntegrationTest {
    private static final String LISTING_SQL = """
            WITH product_listing AS (
                SELECT p.*,
                    encode(set_byte(set_byte(uuid_send(p.seller_id), 0,
                        get_byte(uuid_send(p.seller_id), 0) # 128), 8,
                        get_byte(uuid_send(p.seller_id), 8) # 128), 'hex') COLLATE "C" AS seller_sort_key,
                    encode(set_byte(set_byte(uuid_send(p.product_id), 0,
                        get_byte(uuid_send(p.product_id), 0) # 128), 8,
                        get_byte(uuid_send(p.product_id), 8) # 128), 'hex') COLLATE "C" AS product_sort_key
                FROM product p
            ), cursor_input AS (
                SELECT CAST(? AS uuid) AS cursor_seller, CAST(? AS uuid) AS cursor_product
            ), cursor_key AS (
                SELECT
                    encode(set_byte(set_byte(uuid_send(i.cursor_seller), 0,
                        get_byte(uuid_send(i.cursor_seller), 0) # 128), 8,
                        get_byte(uuid_send(i.cursor_seller), 8) # 128), 'hex') COLLATE "C" AS seller_sort_key,
                    encode(set_byte(set_byte(uuid_send(i.cursor_product), 0,
                        get_byte(uuid_send(i.cursor_product), 0) # 128), 8,
                        get_byte(uuid_send(i.cursor_product), 8) # 128), 'hex') COLLATE "C" AS product_sort_key
                FROM cursor_input i
            )
            SELECT p.product_id, p.seller_id
            FROM product_listing p CROSS JOIN cursor_input i CROSS JOIN cursor_key c
            WHERE p.status = 'ACTIVE'
              AND EXISTS (
                    SELECT 1 FROM product_channel_availability availability
                    WHERE availability.product_id = p.product_id
                      AND availability.sales_channel = 'ONLINE_STORE' AND availability.available = TRUE
              )
              AND (i.cursor_seller IS NULL OR p.seller_sort_key > c.seller_sort_key
                   OR (p.seller_sort_key = c.seller_sort_key AND p.product_sort_key > c.product_sort_key))
            ORDER BY p.seller_sort_key, p.product_sort_key
            LIMIT 101
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
        jdbc.execute("""
                CREATE TABLE product_channel_availability (
                    product_id UUID NOT NULL,
                    sales_channel VARCHAR(32) NOT NULL,
                    available BOOLEAN NOT NULL,
                    PRIMARY KEY (product_id, sales_channel)
                )
                """);
        jdbc.execute(BaselineSchema.createIndex("product_listing_sort_idx"));
    }

    @Test
    void firstAndCursorPagesUseTheExactListingOrderIndexWithoutSorting() {
        jdbc.update("""
                INSERT INTO product (product_id, seller_id, name, description, status)
                SELECT gen_random_uuid(), md5((g % 100)::text)::uuid, 'Product ' || g, 'description', 'ACTIVE'
                FROM generate_series(1, 100000) AS g
                """);
        jdbc.update("""
                INSERT INTO product_channel_availability (product_id, sales_channel, available)
                SELECT product_id, 'ONLINE_STORE', TRUE FROM product
                """);
        jdbc.execute("ANALYZE product");
        jdbc.execute("ANALYZE product_channel_availability");
        UUID[] cursor = jdbc.queryForObject(
                "SELECT seller_id, product_id FROM product ORDER BY product_id LIMIT 1",
                (result, rowNumber) -> new UUID[] {
                    result.getObject("seller_id", UUID.class), result.getObject("product_id", UUID.class)
                });

        List<String> firstPagePlan = explain(null, null);
        List<String> cursorPagePlan = explain(cursor[0], cursor[1]);

        assertOrderedIndexPlan(firstPagePlan);
        assertOrderedIndexPlan(cursorPagePlan);
    }

    private List<String> explain(UUID cursorSeller, UUID cursorProduct) {
        return jdbc.queryForList("EXPLAIN (COSTS OFF) " + LISTING_SQL, String.class,
                cursorSeller, cursorProduct);
    }

    private static void assertOrderedIndexPlan(List<String> plan) {
        String text = String.join("\n", plan);
        assertThat(text)
                .contains("product_listing_sort_idx")
                .doesNotContain("Sort", "Seq Scan on product");
    }
}
