package com.atlas.migration;

import static org.assertj.core.api.Assertions.assertThat;

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
class ProductOrderabilityMigrationIntegrationTest {
    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18.6");

    @Test
    void existingV18ProductsRemainActiveAndEnabledOnBothChannels() {
        String schema = "product_orderability_" + UUID.randomUUID().toString().replace("-", "");
        JdbcTemplate admin = new JdbcTemplate(new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword()));
        admin.execute("CREATE SCHEMA " + schema);
        JdbcTemplate jdbc = new JdbcTemplate(new DriverManagerDataSource(
                POSTGRES.getJdbcUrl() + (POSTGRES.getJdbcUrl().contains("?") ? "&" : "?")
                        + "currentSchema=" + schema, POSTGRES.getUsername(), POSTGRES.getPassword()));
        Flyway.configure().dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .schemas(schema).defaultSchema(schema).locations("classpath:db/migration")
                .target(MigrationVersion.fromVersion("18")).load().migrate();

        UUID seller = UUID.randomUUID();
        UUID product = UUID.randomUUID();
        jdbc.update("INSERT INTO seller(seller_id) VALUES (?)", seller);
        jdbc.update("INSERT INTO product_category(seller_id,code,name) VALUES (?,?,?)",
                seller, "UNCATEGORIZED", "미분류");
        UUID category = jdbc.queryForObject(
                "SELECT category_id FROM product_category WHERE seller_id=? AND code='UNCATEGORIZED'",
                UUID.class, seller);
        jdbc.update("INSERT INTO product(product_id,seller_id,category_id,name) VALUES (?,?,?,?)",
                product, seller, category, "Existing");

        Flyway.configure().dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .schemas(schema).defaultSchema(schema).locations("classpath:db/migration").load().migrate();

        assertThat(jdbc.queryForObject("SELECT status FROM product WHERE product_id=?", String.class, product))
                .isEqualTo("ACTIVE");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM product_channel_availability "
                + "WHERE product_id=? AND available", Integer.class, product)).isEqualTo(2);
        assertThat(jdbc.queryForList("SELECT sales_channel FROM product_channel_availability "
                + "WHERE product_id=? ORDER BY sales_channel", String.class, product))
                .containsExactly("CAMPAIGN", "ONLINE_STORE");
    }
}
