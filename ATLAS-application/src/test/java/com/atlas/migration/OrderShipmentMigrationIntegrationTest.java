package com.atlas.migration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@Testcontainers
class OrderShipmentMigrationIntegrationTest {

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18.6");

    @Test
    void cleanSchemaMigratesWithShipmentTablesAndPartiallyShippedStatus() {
        String schema = createSchema();
        JdbcTemplate jdbc = jdbcForSchema(schema);

        flyway(schema, null).migrate();

        assertThat(tableExists(jdbc, "order_shipment")).isTrue();
        assertThat(tableExists(jdbc, "order_shipment_item")).isTrue();
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM information_schema.check_constraints WHERE constraint_schema=? "
                        + "AND constraint_name='order_fulfillment_status' AND check_clause LIKE '%PARTIALLY_SHIPPED%'",
                Integer.class, schema)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM order_shipment", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT character_maximum_length FROM information_schema.columns "
                + "WHERE table_schema=? AND table_name='order_fulfillment' AND column_name='status'",
                Integer.class, schema)).isEqualTo(24);
    }

    @Test
    void backfillsOnlyShippedStatusesAndExcludesReturnLinkedRefunds() {
        String schema = createSchema();
        JdbcTemplate jdbc = jdbcForSchema(schema);
        flyway(schema, MigrationVersion.fromVersion("16")).migrate();
        Timestamp now = Timestamp.from(Instant.parse("2026-11-07T01:02:03Z"));
        Timestamp shippedAt = Timestamp.from(Instant.parse("2026-11-05T10:00:00Z"));
        Timestamp deliveredAt = Timestamp.from(Instant.parse("2026-11-06T12:00:00Z"));
        Fixture unfulfilled = seedOrder(jdbc, "UNFULFILLED", 3, now, null, null);
        Fixture inProgress = seedOrder(jdbc, "IN_PROGRESS", 4, now, now, null);
        Fixture shipped = seedOrder(jdbc, "SHIPPED", 5, now, now, shippedAt);
        Fixture delivered = seedOrder(jdbc, "DELIVERED", 2, now, now, deliveredAt);
        seedRefund(jdbc, shipped, 1, false, now);
        seedRefund(jdbc, shipped, 2, true, now);

        flyway(schema, null).migrate();

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM order_shipment", Integer.class)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM order_shipment_item", Integer.class)).isEqualTo(2);
        UUID shippedShipment = shipmentId(jdbc, shipped.orderId());
        assertThat(jdbc.queryForObject("SELECT idempotency_key FROM order_shipment WHERE shipment_id=?",
                String.class, shippedShipment)).isNull();
        assertThat(jdbc.queryForObject("SELECT status FROM order_shipment WHERE shipment_id=?", String.class,
                shippedShipment)).isEqualTo("SHIPPED");
        assertThat(jdbc.queryForObject("SELECT shipped_at FROM order_shipment WHERE shipment_id=?", Timestamp.class,
                shippedShipment)).isEqualTo(shippedAt);
        assertThat(jdbc.queryForObject("SELECT delivered_at FROM order_shipment WHERE shipment_id=?", Timestamp.class,
                shippedShipment)).isNull();
        assertThat(jdbc.queryForObject("SELECT quantity FROM order_shipment_item WHERE shipment_id=?",
                Integer.class, shippedShipment)).isEqualTo(4);
        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO order_shipment_item(shipment_id,order_id,order_item_id,quantity) VALUES (?,?,?,1)",
                shippedShipment, shipped.orderId(), delivered.orderItemId()))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO order_shipment_item(shipment_id,order_id,order_item_id,quantity) VALUES (?,?,?,1)",
                shippedShipment, delivered.orderId(), delivered.orderItemId()))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO order_item(
                    order_item_id, order_id, product_id, seller_id, sku_id, product_name, quantity, unit_price_won
                )
                SELECT ?, ?, product_id, seller_id, sku_id, 'Cross-seller line', 1, 100
                FROM order_item WHERE order_item_id = ?
                """, UUID.randomUUID(), shipped.orderId(), delivered.orderItemId()))
                .isInstanceOf(DataIntegrityViolationException.class);
        UUID deliveredShipment = shipmentId(jdbc, delivered.orderId());
        assertThat(jdbc.queryForObject("SELECT status FROM order_shipment WHERE shipment_id=?", String.class,
                deliveredShipment)).isEqualTo("DELIVERED");
        assertThat(jdbc.queryForObject("SELECT shipped_at FROM order_shipment WHERE shipment_id=?", Timestamp.class,
                deliveredShipment)).isEqualTo(deliveredAt);
        assertThat(jdbc.queryForObject("SELECT delivered_at FROM order_shipment WHERE shipment_id=?", Timestamp.class,
                deliveredShipment)).isEqualTo(deliveredAt);
        assertThat(jdbc.queryForObject("SELECT quantity FROM order_shipment_item WHERE shipment_id=?",
                Integer.class, deliveredShipment)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM order_shipment WHERE order_id IN (?,?)",
                Integer.class, unfulfilled.orderId(), inProgress.orderId())).isZero();
        assertThat(jdbc.queryForObject("SELECT status FROM order_fulfillment WHERE order_id=?", String.class,
                unfulfilled.orderId())).isEqualTo("UNFULFILLED");
        assertThat(jdbc.queryForObject("SELECT status FROM order_fulfillment WHERE order_id=?", String.class,
                inProgress.orderId())).isEqualTo("IN_PROGRESS");
        assertThat(jdbc.queryForObject("SELECT in_progress_at FROM order_fulfillment WHERE order_id=?", Timestamp.class,
                inProgress.orderId())).isEqualTo(now);
        assertThat(jdbc.queryForObject("SELECT shipped_at FROM order_fulfillment WHERE order_id=?", Timestamp.class,
                shipped.orderId())).isEqualTo(shippedAt);
        assertThat(jdbc.queryForObject("SELECT delivered_at FROM order_fulfillment WHERE order_id=?", Timestamp.class,
                delivered.orderId())).isEqualTo(deliveredAt);
        assertThat(jdbc.queryForObject("SELECT carrier_code IS NULL AND tracking_number IS NULL FROM order_shipment WHERE shipment_id=?",
                Boolean.class, shippedShipment)).isTrue();
    }

    @Test
    void refusesHistoricalFulfillmentWithNoRemainingShippableQuantity() {
        String schema = createSchema();
        JdbcTemplate jdbc = jdbcForSchema(schema);
        flyway(schema, MigrationVersion.fromVersion("16")).migrate();
        Timestamp now = Timestamp.from(Instant.parse("2026-11-07T01:02:03Z"));
        Fixture shipped = seedOrder(jdbc, "SHIPPED", 2, now, now, now);
        seedRefund(jdbc, shipped, 2, false, now);

        assertThatThrownBy(() -> flyway(schema, null).migrate())
                .isInstanceOf(FlywayException.class)
                .hasStackTraceContaining("V17 cannot backfill SHIPPED/DELIVERED fulfillment with zero shippable quantity");

        assertThat(tableExists(jdbc, "order_shipment")).isFalse();
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM flyway_schema_history WHERE version='17' AND success", Integer.class)).isZero();
    }

    private Fixture seedOrder(JdbcTemplate jdbc, String fulfillmentStatus, int quantity,
            Timestamp createdAt, Timestamp inProgressAt, Timestamp shippedAt) {
        UUID sellerId = UUID.randomUUID();
        UUID productId = UUID.randomUUID();
        UUID locationId = UUID.randomUUID();
        UUID orderId = UUID.randomUUID();
        UUID orderItemId = UUID.randomUUID();
        UUID checkoutId = UUID.randomUUID();
        String buyerRef = "buyer-" + orderId;
        String sku = "shipment-migration-" + UUID.randomUUID();
        jdbc.update("INSERT INTO seller(seller_id) VALUES (?)", sellerId);
        jdbc.update("INSERT INTO product(product_id,seller_id,name) VALUES (?,?,?)", productId, sellerId, "Fixture");
        jdbc.update("INSERT INTO seller_sku(seller_id,sku_id,created_at,product_id,price_won) VALUES (?,?,?,?,?)",
                sellerId, sku, createdAt, productId, 100L);
        jdbc.update("INSERT INTO inventory_location(location_id,seller_id,location_code,name) VALUES (?,?,?,?)",
                locationId, sellerId, "LOC-" + UUID.randomUUID(), "Fixture location");
        jdbc.update("""
                INSERT INTO buyer_checkout(checkout_id,buyer_ref,idempotency_key,state,total_won,created_at,updated_at)
                VALUES (?,?,?,'PAID',?,?,?)
                """, checkoutId, buyerRef, "shipment-migration-" + checkoutId, quantity * 100L, createdAt,
                createdAt);
        jdbc.update("""
                INSERT INTO purchase_order(order_id,seller_id,buyer_ref,state,checkout_expires_at,created_at,updated_at,
                    order_source,checkout_id,fulfillment_location_id)
                VALUES (?,?,?,'PAID',?,?,?,'CART',?,?)
                """, orderId, sellerId, buyerRef, createdAt, createdAt, createdAt, checkoutId, locationId);
        jdbc.update("""
                INSERT INTO order_item(order_item_id,order_id,product_id,seller_id,sku_id,product_name,quantity,unit_price_won)
                VALUES (?,?,?,?,?,?,?,100)
                """, orderItemId, orderId, productId, sellerId, sku, "Fixture", quantity);
        jdbc.update("""
                INSERT INTO order_fulfillment(order_id,status,created_at,updated_at,in_progress_at,shipped_at,delivered_at)
                VALUES (?,?,?,?,?,?,?)
                """, orderId, fulfillmentStatus, createdAt, createdAt, inProgressAt, shippedAt,
                "DELIVERED".equals(fulfillmentStatus) ? shippedAt : null);
        return new Fixture(orderId, orderItemId);
    }

    private void seedRefund(JdbcTemplate jdbc, Fixture fixture, int quantity, boolean returnLinked, Timestamp now) {
        UUID refundId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO order_refund_request(refund_id,order_id,seller_id,idempotency_key,amount_won,refund_status,created_at)
                SELECT ?,o.order_id,o.seller_id,?,?,'PARTIALLY_RECORDED',? FROM purchase_order o WHERE o.order_id=?
                """, refundId, "refund-" + refundId, quantity * 100L, now, fixture.orderId());
        jdbc.update("INSERT INTO order_refund_request_item(refund_id,order_item_id,quantity) VALUES (?,?,?)",
                refundId, fixture.orderItemId(), quantity);
        if (returnLinked) {
            UUID returnId = UUID.randomUUID();
            jdbc.update("""
                    INSERT INTO order_return(return_id,order_id,seller_id,buyer_ref,idempotency_key,return_status,
                        requested_at,received_at,inspected_at)
                    SELECT ?,o.order_id,o.seller_id,o.buyer_ref,?,'REFUND_RECORDED',?,?,? FROM purchase_order o WHERE o.order_id=?
                    """, returnId, "return-" + returnId, now, now, now, fixture.orderId());
            jdbc.update("""
                    INSERT INTO order_return_item(return_id,order_item_id,quantity,inspection_result,refund_id,
                        refund_amount_won,inspected_at)
                    VALUES (?,?,?,'PASSED',?,?,?)
                    """, returnId, fixture.orderItemId(), quantity, refundId, quantity * 100L, now);
        }
    }

    private UUID shipmentId(JdbcTemplate jdbc, UUID orderId) {
        return jdbc.queryForObject("SELECT shipment_id FROM order_shipment WHERE order_id=?", UUID.class, orderId);
    }

    private String createSchema() {
        String schema = "order_shipment_" + UUID.randomUUID().toString().replace("-", "");
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
        return Boolean.TRUE.equals(jdbc.queryForObject("SELECT to_regclass(?) IS NOT NULL", Boolean.class, table));
    }

    private record Fixture(UUID orderId, UUID orderItemId) {}
}
