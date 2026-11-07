package com.atlas.migration;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Timestamp;
import java.time.Instant;
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
class InventoryLocationMigrationIntegrationTest {
    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18.6");

    @Test
    void freshSchemaMigratesThroughV16() {
        String schema = createSchema();
        JdbcTemplate jdbc = jdbcForSchema(schema);
        flyway(schema, null).migrate();

        assertThat(tableExists(jdbc, "inventory_location")).isTrue();
        assertThat(tableExists(jdbc, "inventory_transfer")).isTrue();
        assertThat(columnExists(jdbc, "inventory_pool", "location_id")).isTrue();
        assertThat(columnExists(jdbc, "inventory_reservation", "location_id")).isTrue();
        assertThat(columnExists(jdbc, "purchase_order", "fulfillment_location_id")).isTrue();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM flyway_schema_history WHERE version='16' AND success",
                Integer.class)).isEqualTo(1);
    }

    @Test
    void populatedV15RowsMapToDefaultWithoutChangingStockOrReservations() {
        String schema = createSchema();
        JdbcTemplate jdbc = jdbcForSchema(schema);
        flyway(schema, MigrationVersion.fromVersion("15")).migrate();
        Fixture fixture = seedV15Rows(jdbc);

        assertThat(jdbc.queryForObject("SELECT available+reserved+claimed FROM inventory_pool WHERE seller_id=? AND sku_id=?",
                Integer.class, fixture.sellerId(), fixture.skuId())).isEqualTo(10);
        flyway(schema, null).migrate();

        UUID defaultId = jdbc.queryForObject(
                "SELECT location_id FROM inventory_location WHERE seller_id=? AND location_code='DEFAULT'",
                UUID.class, fixture.sellerId());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM inventory_location WHERE seller_id=?",
                Integer.class, fixture.sellerId())).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT location_id FROM inventory_pool WHERE seller_id=? AND sku_id=?",
                UUID.class, fixture.sellerId(), fixture.skuId())).isEqualTo(defaultId);
        assertThat(jdbc.queryForObject("SELECT location_id FROM inventory_reservation WHERE order_id=?",
                UUID.class, fixture.orderId())).isEqualTo(defaultId);
        assertThat(jdbc.queryForObject("SELECT fulfillment_location_id FROM purchase_order WHERE order_id=?",
                UUID.class, fixture.orderId())).isEqualTo(defaultId);
        assertThat(jdbc.queryForObject("SELECT initial_capacity FROM inventory_pool WHERE seller_id=? AND sku_id=?",
                Integer.class, fixture.sellerId(), fixture.skuId())).isEqualTo(10);
        assertThat(jdbc.queryForObject("SELECT available FROM inventory_pool WHERE seller_id=? AND sku_id=?",
                Integer.class, fixture.sellerId(), fixture.skuId())).isEqualTo(5);
        assertThat(jdbc.queryForObject("SELECT reserved FROM inventory_pool WHERE seller_id=? AND sku_id=?",
                Integer.class, fixture.sellerId(), fixture.skuId())).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT claimed FROM inventory_pool WHERE seller_id=? AND sku_id=?",
                Integer.class, fixture.sellerId(), fixture.skuId())).isEqualTo(3);
        assertThat(jdbc.queryForObject("SELECT quantity FROM inventory_reservation WHERE order_id=?",
                Integer.class, fixture.orderId())).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM inventory_capacity_adjustment WHERE seller_id=? AND sku_id=?",
                Integer.class, fixture.sellerId(), fixture.skuId())).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM purchase_order WHERE order_id=?",
                Integer.class, fixture.orderId())).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM inventory_reservation WHERE reservation_id=?",
                Integer.class, fixture.reservationId())).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT available+reserved+claimed FROM inventory_pool WHERE seller_id=? AND sku_id=?",
                Integer.class, fixture.sellerId(), fixture.skuId())).isEqualTo(10);
        assertThat(jdbc.queryForObject("SELECT idempotency_key FROM inventory_capacity_adjustment WHERE seller_id=? AND sku_id=?",
                String.class, fixture.sellerId(), fixture.skuId())).isEqualTo("migration-adjust-key");
        assertThat(jdbc.queryForObject("SELECT last_sequence FROM admission_counter WHERE seller_id=? AND sku_id=?",
                Long.class, fixture.sellerId(), fixture.skuId())).isEqualTo(1L);
        assertThat(jdbc.queryForObject("SELECT state FROM purchase_order WHERE order_id=?",
                String.class, fixture.orderId())).isEqualTo("PAYMENT_PENDING");
        assertThat(jdbc.queryForObject("SELECT status FROM inventory_reservation WHERE order_id=?",
                String.class, fixture.orderId())).isEqualTo("RESERVED");
    }

    private Fixture seedV15Rows(JdbcTemplate jdbc) {
        UUID sellerId = UUID.randomUUID();
        UUID productId = UUID.randomUUID();
        UUID campaignId = UUID.randomUUID();
        UUID ticketId = UUID.randomUUID();
        UUID orderId = UUID.randomUUID();
        UUID reservationId = UUID.randomUUID();
        String skuId = "migration-location-" + UUID.randomUUID();
        Timestamp now = Timestamp.from(Instant.parse("2026-11-07T00:00:00Z"));
        Timestamp expiresAt = Timestamp.from(now.toInstant().plusSeconds(300));
        jdbc.update("INSERT INTO seller(seller_id) VALUES (?)", sellerId);
        jdbc.update("INSERT INTO product(product_id,seller_id,name) VALUES (?,?,?)", productId, sellerId, "Migration fixture");
        jdbc.update("INSERT INTO seller_sku(seller_id,sku_id,created_at,product_id,price_won) VALUES (?,?,?,?,?)",
                sellerId, skuId, now, productId, 100L);
        jdbc.update("INSERT INTO campaign(campaign_id,seller_id,sku_id,status,queue_ticket_ttl_seconds,created_at) VALUES (?,?,?,?,?,?)",
                campaignId, sellerId, skuId, "OPEN", 300L, now);
        jdbc.update("INSERT INTO admission_counter(seller_id,sku_id,last_sequence) VALUES (?,?,?)", sellerId, skuId, 1L);
        jdbc.update("""
                INSERT INTO admission_ticket(ticket_id,campaign_id,seller_id,sku_id,buyer_ref,idempotency_key,
                    sequence,status,expires_at,created_at,checkout_slot_acquired_at,checkout_slot_held)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?)
                """, ticketId, campaignId, sellerId, skuId, "migration-buyer", "migration-ticket-key",
                1L, "CHECKOUT_STARTED", expiresAt, now, now, true);
        jdbc.update("INSERT INTO inventory_pool(seller_id,sku_id,initial_capacity,available,reserved,claimed) VALUES (?,?,?,?,?,?)",
                sellerId, skuId, 10, 5, 2, 3);
        jdbc.update("""
                INSERT INTO purchase_order(order_id,seller_id,campaign_id,ticket_id,buyer_ref,state,
                    checkout_expires_at,payment_attempt_id,created_at,updated_at,order_source,checkout_key,checkout_id)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?)
                """, orderId, sellerId, campaignId, ticketId, "migration-buyer", "PAYMENT_PENDING",
                expiresAt, null, now, now, "CAMPAIGN", null, null);
        jdbc.update("""
                INSERT INTO inventory_reservation(order_id,campaign_id,seller_id,sku_id,quantity,status,
                    reservation_id,restored_quantity)
                VALUES (?,?,?,?,?,?,?,?)
                """, orderId, campaignId, sellerId, skuId, 2, "RESERVED", reservationId, 0);
        jdbc.update("""
                INSERT INTO inventory_capacity_adjustment(seller_id,sku_id,idempotency_key,delta,reason,
                    initial_capacity,available,reserved,claimed)
                VALUES (?,?,?,?,?,?,?,?,?)
                """, sellerId, skuId, "migration-adjust-key", 1, "migration adjustment", 10, 5, 2, 3);
        return new Fixture(sellerId, skuId, orderId, reservationId);
    }

    private String createSchema() {
        String schema = "inventory_location_" + UUID.randomUUID().toString().replace("-", "");
        JdbcTemplate admin = new JdbcTemplate(new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword()));
        admin.execute("CREATE SCHEMA " + schema);
        return schema;
    }

    private JdbcTemplate jdbcForSchema(String schema) {
        String url = POSTGRES.getJdbcUrl() + (POSTGRES.getJdbcUrl().contains("?") ? "&" : "?")
                + "currentSchema=" + schema;
        return new JdbcTemplate(new DriverManagerDataSource(url, POSTGRES.getUsername(), POSTGRES.getPassword()));
    }

    private Flyway flyway(String schema, MigrationVersion target) {
        var configuration = Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .schemas(schema)
                .defaultSchema(schema)
                .locations("classpath:db/migration");
        if (target != null) {
            configuration.target(target);
        }
        return configuration.load();
    }

    private boolean tableExists(JdbcTemplate jdbc, String table) {
        return Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS (SELECT 1 FROM information_schema.tables WHERE table_schema=current_schema() AND table_name=?)",
                Boolean.class, table));
    }

    private boolean columnExists(JdbcTemplate jdbc, String table, String column) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=current_schema() AND table_name=? AND column_name=?",
                Integer.class, table, column) == 1;
    }

    private record Fixture(UUID sellerId, String skuId, UUID orderId, UUID reservationId) {}
}
