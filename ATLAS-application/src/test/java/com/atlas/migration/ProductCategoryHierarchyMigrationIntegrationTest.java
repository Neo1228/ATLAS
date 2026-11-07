package com.atlas.migration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@Testcontainers
class ProductCategoryHierarchyMigrationIntegrationTest {
    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18.6");

    @Test
    void addsNullableParentToExistingCategoriesAsRootsAndEnforcesSellerScopedParent() {
        String schema = "category_hierarchy_" + UUID.randomUUID().toString().replace("-", "");
        JdbcTemplate admin = new JdbcTemplate(new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword()));
        admin.execute("CREATE SCHEMA " + schema);
        migrate(schema, MigrationVersion.fromVersion("19"));
        JdbcTemplate jdbc = jdbc(schema);

        UUID sellerA = UUID.randomUUID();
        UUID sellerB = UUID.randomUUID();
        UUID productId = UUID.randomUUID();
        jdbc.update("INSERT INTO seller (seller_id) VALUES (?), (?)", sellerA, sellerB);
        jdbc.update("INSERT INTO product_category (seller_id, code, name) VALUES "
                + "(?, 'UNCATEGORIZED', '미분류'), (?, 'UNCATEGORIZED', '미분류')", sellerA, sellerB);
        UUID categoryA = jdbc.queryForObject(
                "SELECT category_id FROM product_category WHERE seller_id = ? AND code = 'UNCATEGORIZED'",
                UUID.class, sellerA);
        UUID categoryB = jdbc.queryForObject(
                "SELECT category_id FROM product_category WHERE seller_id = ? AND code = 'UNCATEGORIZED'",
                UUID.class, sellerB);
        jdbc.update("INSERT INTO product (product_id, seller_id, category_id, name, description) "
                + "VALUES (?, ?, ?, ?, ?)", productId, sellerA, categoryA, "existing product", "existing");
        migrate(schema, null);

        assertThat(jdbc.queryForObject("SELECT parent_category_id FROM product_category WHERE category_id = ?",
                UUID.class, categoryA)).isNull();
        assertThat(jdbc.queryForObject("SELECT category_id FROM product WHERE product_id = ?", UUID.class, productId))
                .isEqualTo(categoryA);
        assertThat(jdbc.queryForObject("SELECT seller_id FROM product WHERE product_id = ?", UUID.class, productId))
                .isEqualTo(sellerA);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM product WHERE seller_id = ? AND category_id = ?",
                Integer.class, sellerA, categoryA)).isEqualTo(1);
        UUID child = UUID.randomUUID();
        jdbc.update("INSERT INTO product_category (category_id, seller_id, code, name, parent_category_id) "
                + "VALUES (?, ?, 'CHILD', 'Child', ?)", child, sellerA, categoryA);
        assertThatThrownBy(() -> jdbc.update("INSERT INTO product_category "
                + "(category_id, seller_id, code, name, parent_category_id) VALUES (?, ?, 'BAD', 'Bad', ?)",
                UUID.randomUUID(), sellerA, categoryB)).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("UPDATE product_category SET parent_category_id = category_id "
                + "WHERE category_id = ?", child)).isInstanceOf(DataIntegrityViolationException.class);
    }

    private static JdbcTemplate jdbc(String schema) {
        String url = POSTGRES.getJdbcUrl() + (POSTGRES.getJdbcUrl().contains("?") ? "&" : "?")
                + "currentSchema=" + schema;
        return new JdbcTemplate(new DriverManagerDataSource(url, POSTGRES.getUsername(), POSTGRES.getPassword()));
    }

    private static void migrate(String schema, MigrationVersion target) {
        var configuration = Flyway.configure().dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .schemas(schema).defaultSchema(schema).locations("classpath:db/migration");
        if (target != null) configuration.target(target);
        configuration.load().migrate();
    }
}
