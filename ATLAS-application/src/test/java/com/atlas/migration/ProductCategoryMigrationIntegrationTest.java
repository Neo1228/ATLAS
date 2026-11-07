package com.atlas.migration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@Testcontainers
class ProductCategoryMigrationIntegrationTest {
    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18.6");

    @Test
    void cleanSchemaMigratesThroughV18() {
        String schema = createSchema();
        JdbcTemplate jdbc = jdbcForSchema(schema);
        flyway(schema, null).migrate();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM product_category", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT is_nullable FROM information_schema.columns WHERE table_schema=current_schema() AND table_name='product' AND column_name='category_id'", String.class)).isEqualTo("NO");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM flyway_schema_history WHERE version='18' AND success", Integer.class)).isEqualTo(1);
    }

    @Test
    void populatedV17ProductsBackfillToSellerDefaultsAndCompositeForeignKeyRejectsMismatch() {
        String schema = createSchema();
        JdbcTemplate jdbc = jdbcForSchema(schema);
        flyway(schema, MigrationVersion.fromVersion("17")).migrate();
        UUID firstSeller = UUID.randomUUID();
        UUID secondSeller = UUID.randomUUID();
        UUID emptySeller = UUID.randomUUID();
        UUID firstProduct = UUID.randomUUID();
        UUID secondProduct = UUID.randomUUID();
        jdbc.update("INSERT INTO seller(seller_id) VALUES (?), (?), (?)", firstSeller, secondSeller, emptySeller);
        jdbc.update("INSERT INTO product(product_id,seller_id,name) VALUES (?,?,?), (?,?,?)",
                firstProduct, firstSeller, "First", secondProduct, secondSeller, "Second");

        flyway(schema, null).migrate();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM product", Integer.class)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM product_category WHERE code='UNCATEGORIZED' AND name='미분류'", Integer.class)).isEqualTo(3);
        UUID firstDefault = defaultCategory(jdbc, firstSeller);
        UUID secondDefault = defaultCategory(jdbc, secondSeller);
        UUID emptySellerDefault = defaultCategory(jdbc, emptySeller);
        assertThat(jdbc.queryForObject("SELECT category_id FROM product WHERE product_id=?", UUID.class, firstProduct)).isEqualTo(firstDefault);
        assertThat(jdbc.queryForObject("SELECT category_id FROM product WHERE product_id=?", UUID.class, secondProduct)).isEqualTo(secondDefault);
        assertThat(jdbc.queryForObject("SELECT substring(category_id::text, 15, 1) FROM product_category WHERE category_id=?", String.class, firstDefault)).isEqualTo("7");
        assertThat(jdbc.queryForObject("SELECT seller_id FROM product WHERE product_id=?", UUID.class, firstProduct))
                .isEqualTo(firstSeller);
        assertThat(jdbc.queryForObject("SELECT name FROM product WHERE product_id=?", String.class, firstProduct))
                .isEqualTo("First");
        assertThat(jdbc.queryForObject("SELECT seller_id FROM product WHERE product_id=?", UUID.class, secondProduct))
                .isEqualTo(secondSeller);
        assertThat(jdbc.queryForObject("SELECT name FROM product WHERE product_id=?", String.class, secondProduct))
                .isEqualTo("Second");
        assertThat(jdbc.queryForObject("SELECT substring(category_id::text, 15, 1) FROM product_category WHERE category_id=?",
                String.class, emptySellerDefault)).isEqualTo("7");
        UUID arbitrary = UUID.randomUUID();
        jdbc.update("INSERT INTO product_category(category_id,seller_id,code,name) VALUES (?,?,?,?)", arbitrary, firstSeller, "OTHER", "Other");
        jdbc.update("UPDATE product SET category_id=? WHERE product_id=?", arbitrary, firstProduct);
        assertThat(jdbc.queryForObject("SELECT category_id FROM product WHERE product_id=?", UUID.class, firstProduct)).isEqualTo(arbitrary);
        assertThatThrownBy(() -> jdbc.update("UPDATE product SET category_id=? WHERE product_id=?", arbitrary, secondProduct))
                .isInstanceOf(RuntimeException.class);
    }

    private UUID defaultCategory(JdbcTemplate jdbc, UUID sellerId) {
        return jdbc.queryForObject("SELECT category_id FROM product_category WHERE seller_id=? AND code='UNCATEGORIZED'", UUID.class, sellerId);
    }

    private String createSchema() {
        String schema = "product_category_" + UUID.randomUUID().toString().replace("-", "");
        JdbcTemplate admin = new JdbcTemplate(new DriverManagerDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword()));
        admin.execute("CREATE SCHEMA " + schema);
        return schema;
    }

    private JdbcTemplate jdbcForSchema(String schema) {
        String url = POSTGRES.getJdbcUrl() + (POSTGRES.getJdbcUrl().contains("?") ? "&" : "?") + "currentSchema=" + schema;
        return new JdbcTemplate(new DriverManagerDataSource(url, POSTGRES.getUsername(), POSTGRES.getPassword()));
    }

    private Flyway flyway(String schema, MigrationVersion target) {
        var configuration = Flyway.configure().dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .schemas(schema).defaultSchema(schema).locations("classpath:db/migration");
        if (target != null) configuration.target(target);
        return configuration.load();
    }
}
