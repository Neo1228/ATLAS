package com.atlas.migration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
class SellerSkuGlobalFifoMigrationIntegrationTest {

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18.6");

    private String schema;
    private JdbcTemplate jdbc;

    @BeforeEach
    void prepareLegacySchema() {
        schema = createSchema();
        jdbc = jdbcForSchema(schema);
        flyway(schema, MigrationVersion.fromVersion("5")).migrate();
    }

    @Test
    void emptyV6SchemaMigratesAllCurrentApplicationModules() {
        flyway(schema, null).migrate();

        assertThat(tableExists(jdbc, "seller")).isTrue();
        assertThat(tableExists(jdbc, "seller_sku")).isTrue();
        assertThat(tableExists(jdbc, "campaign")).isTrue();
        assertThat(tableExists(jdbc, "admission_counter")).isTrue();
        assertThat(tableExists(jdbc, "admission_ticket")).isTrue();
        assertThat(tableExists(jdbc, "inventory_pool")).isTrue();
        assertThat(tableExists(jdbc, "inventory_reservation")).isTrue();
        assertThat(tableExists(jdbc, "inventory_location")).isTrue();
        assertThat(tableExists(jdbc, "inventory_transfer")).isTrue();
        assertThat(tableExists(jdbc, "purchase_order")).isTrue();
        assertThat(tableExists(jdbc, "payment_attempt")).isTrue();
        assertThat(tableExists(jdbc, "product")).isTrue();
        assertThat(tableExists(jdbc, "seller_sku_admission_policy")).isTrue();
        assertThat(tableExists(jdbc, "shopping_cart")).isTrue();
        assertThat(tableExists(jdbc, "shopping_cart_item")).isTrue();
        assertThat(tableExists(jdbc, "order_item")).isTrue();
        assertThat(tableExists(jdbc, "order_reorder_request")).isTrue();
        assertThat(columnExists(jdbc, "seller_sku", "product_id")).isTrue();
        assertThat(columnExists(jdbc, "seller_sku", "price_won")).isTrue();
        assertThat(columnExists(jdbc, "seller_sku", "max_active_checkouts")).isFalse();
        assertThat(columnExists(jdbc, "inventory_reservation", "reservation_id")).isTrue();
        assertThat(columnExists(jdbc, "purchase_order", "order_source")).isTrue();
        assertThat(columnExists(jdbc, "purchase_order", "checkout_key")).isTrue();
        assertThat(columnExists(jdbc, "purchase_order", "checkout_id")).isTrue();
        assertThat(columnExists(jdbc, "purchase_order", "sku_id")).isFalse();
        assertThat(columnExists(jdbc, "campaign", "seller_id")).isTrue();
        assertThat(columnExists(jdbc, "campaign", "max_active_checkouts")).isFalse();
        assertThat(columnExists(jdbc, "admission_ticket", "seller_id")).isTrue();
        assertThat(columnExists(jdbc, "inventory_pool", "seller_id")).isTrue();
        assertThat(columnExists(jdbc, "inventory_reservation", "seller_id")).isTrue();
        assertThat(columnExists(jdbc, "purchase_order", "seller_id")).isTrue();
        assertThat(columnExists(jdbc, "payment_attempt", "payment_phase")).isTrue();
        assertThat(columnExists(jdbc, "payment_attempt", "checkout_id")).isTrue();
        assertThat(jdbc.queryForObject("""
                SELECT kcu.column_name
                FROM information_schema.table_constraints tc
                JOIN information_schema.key_column_usage kcu
                  ON tc.constraint_name = kcu.constraint_name
                 AND tc.constraint_schema = kcu.constraint_schema
                WHERE tc.table_schema = ? AND tc.table_name = 'payment_attempt'
                  AND tc.constraint_type = 'PRIMARY KEY'
                """, String.class, schema)).isEqualTo("attempt_id");
        assertThat(columnExists(jdbc, "shopping_cart", "seller_id")).isFalse();
        assertThat(columnExists(jdbc, "settlement_journal", "checkout_id")).isTrue();
        assertThat(tableExists(jdbc, "campaign_seller_backfill")).isFalse();
        assertThat(tableExists(jdbc, "settlement_journal")).isTrue();
        assertThat(tableExists(jdbc, "settlement_entry")).isTrue();
        assertThat(tableExists(jdbc, "buyer_checkout")).isTrue();
        assertThat(tableExists(jdbc, "settlement_seller_allocation")).isTrue();
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = ? AND table_type = 'BASE TABLE'",
                Integer.class, schema)).isEqualTo(32); // thirty-one application tables plus Flyway history
    }

    @Test
    void v7RefusesExistingV6RowsBeforeChangingTheSchema() {
        String populatedSchema = createSchema();
        JdbcTemplate populatedJdbc = jdbcForSchema(populatedSchema);
        flyway(populatedSchema, MigrationVersion.fromVersion("6")).migrate();
        UUID sellerId = UUID.randomUUID();
        populatedJdbc.update("INSERT INTO seller (seller_id) VALUES (?)", sellerId);

        assertThatThrownBy(() -> flyway(populatedSchema, null).migrate())
                .isInstanceOf(FlywayException.class)
                .hasStackTraceContaining("V7 requires an empty local application schema: existing Seller-SKU rows have no Product/price mapping");

        assertThat(populatedJdbc.queryForObject("SELECT COUNT(*) FROM seller", Integer.class)).isEqualTo(1);
        assertThat(tableExists(populatedJdbc, "product")).isFalse();
        assertThat(populatedJdbc.queryForObject(
                "SELECT COUNT(*) FROM flyway_schema_history WHERE version = '7' AND success",
                Integer.class)).isZero();
    }

    @Test
    void refusesRowsInEveryLegacyApplicationTableWithoutMutation() {
        for (String populatedTable : new String[]{
                "campaign", "admission_counter", "admission_ticket", "inventory_pool",
                "inventory_reservation", "purchase_order", "payment_attempt"}) {
            String isolatedSchema = createSchema();
            JdbcTemplate isolatedJdbc = jdbcForSchema(isolatedSchema);
            flyway(isolatedSchema, MigrationVersion.fromVersion("5")).migrate();
            seedLegacyRow(isolatedJdbc, populatedTable);

            assertThatThrownBy(() -> flyway(isolatedSchema, null).migrate())
                    .isInstanceOf(FlywayException.class)
                    .hasStackTraceContaining("clean-start database");

            assertThat(isolatedJdbc.queryForObject("SELECT COUNT(*) FROM " + populatedTable, Integer.class))
                    .isEqualTo(1);
            assertThat(columnExists(isolatedJdbc, "campaign", "seller_id")).isFalse();
            assertThat(columnExists(isolatedJdbc, "campaign", "max_active_checkouts")).isTrue();
            assertThat(tableExists(isolatedJdbc, "seller")).isFalse();
            assertThat(tableExists(isolatedJdbc, "campaign_seller_backfill")).isFalse();
            assertThat(isolatedJdbc.queryForObject(
                    "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = ? AND table_type = 'BASE TABLE'",
                    Integer.class, isolatedSchema)).isEqualTo(8); // seven legacy tables plus Flyway history
            assertThat(isolatedJdbc.queryForObject(
                    "SELECT COUNT(*) FROM flyway_schema_history WHERE version = '6' AND success",
                    Integer.class)).isZero();
        }
    }

    @Test
    void v14RefusesRowsInEveryRelevantLegacyCommerceTableBeforeSchemaMutation() {
        for (String populatedTable : new String[]{
                "shopping_cart", "shopping_cart_item", "purchase_order", "payment_attempt",
                "settlement_journal", "settlement_entry"}) {
            String isolatedSchema = createSchema();
            JdbcTemplate isolatedJdbc = jdbcForSchema(isolatedSchema);
            flyway(isolatedSchema, MigrationVersion.fromVersion("13")).migrate();
            seedV14GuardRow(isolatedJdbc, populatedTable);

            assertThatThrownBy(() -> flyway(isolatedSchema, null).migrate())
                    .isInstanceOf(FlywayException.class)
                    .hasStackTraceContaining("V14 requires a clean-start database");

            assertThat(isolatedJdbc.queryForObject("SELECT COUNT(*) FROM " + populatedTable, Integer.class))
                    .isEqualTo(1);
            assertThat(tableExists(isolatedJdbc, "buyer_checkout")).isFalse();
            assertThat(columnExists(isolatedJdbc, "purchase_order", "checkout_id")).isFalse();
            assertThat(columnExists(isolatedJdbc, "payment_attempt", "checkout_id")).isFalse();
            assertThat(columnExists(isolatedJdbc, "settlement_journal", "checkout_id")).isFalse();
            assertThat(columnExists(isolatedJdbc, "shopping_cart", "seller_id")).isTrue();
            assertThat(tableExists(isolatedJdbc, "settlement_seller_allocation")).isFalse();
            assertThat(isolatedJdbc.queryForObject(
                    "SELECT COUNT(*) FROM flyway_schema_history WHERE version = '14' AND success",
                    Integer.class)).isZero();
        }
    }

    private void seedV14GuardRow(JdbcTemplate target, String table) {
        switch (table) {
            case "shopping_cart" -> {
                UUID sellerId = UUID.randomUUID();
                target.update("INSERT INTO seller (seller_id) VALUES (?)", sellerId);
                target.update(
                        "INSERT INTO shopping_cart (cart_id, seller_id, buyer_ref) "
                                + "VALUES (?, ?, 'buyer')", UUID.randomUUID(), sellerId);
            }
            case "shopping_cart_item" -> {
                UUID sellerId = UUID.randomUUID();
                UUID productId = UUID.randomUUID();
                String sku = "sku-" + UUID.randomUUID();
                target.update("INSERT INTO seller (seller_id) VALUES (?)", sellerId);
                target.update("INSERT INTO product (product_id, seller_id, name) VALUES (?, ?, 'product')",
                        productId, sellerId);
                target.update("INSERT INTO seller_sku (seller_id, sku_id, product_id, price_won, created_at) "
                                + "VALUES (?, ?, ?, 100, CURRENT_TIMESTAMP)",
                        sellerId, sku, productId);
                UUID cartId = UUID.randomUUID();
                target.update("INSERT INTO shopping_cart (cart_id, seller_id, buyer_ref) VALUES (?, ?, 'buyer')",
                        cartId, sellerId);
                target.update("INSERT INTO shopping_cart_item (cart_item_id, cart_id, seller_id, sku_id, quantity) "
                        + "VALUES (?, ?, ?, ?, 1)", UUID.randomUUID(), cartId, sellerId, sku);
            }
            case "purchase_order" -> target.update(
                    "INSERT INTO purchase_order (order_id, buyer_ref, seller_id, state, checkout_expires_at, "
                            + "created_at, updated_at, order_source) "
                            + "VALUES (?, 'buyer', ?, 'RESERVED', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'CART')",
                    UUID.randomUUID(), UUID.randomUUID());
            case "payment_attempt" -> {
                UUID orderId = UUID.randomUUID();
                target.update("INSERT INTO purchase_order (order_id, buyer_ref, seller_id, state, checkout_expires_at, "
                                + "created_at, updated_at, order_source) "
                                + "VALUES (?, 'buyer', ?, 'RESERVED', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'CART')",
                        orderId, UUID.randomUUID());
                target.update("INSERT INTO payment_attempt (order_id, attempt_id, state, payment_phase, authorization_active) "
                        + "VALUES (?, ?, 'NOT_STARTED', 'AUTHORIZATION', FALSE)", orderId, UUID.randomUUID());
            }
            case "settlement_journal" -> {
                UUID orderId = UUID.randomUUID();
                UUID sellerId = UUID.randomUUID();
                target.update("INSERT INTO seller (seller_id) VALUES (?)", sellerId);
                target.update("INSERT INTO purchase_order (order_id, buyer_ref, seller_id, state, checkout_expires_at, "
                                + "created_at, updated_at, order_source) "
                                + "VALUES (?, 'buyer', ?, 'PAID', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'CART')",
                        orderId, sellerId);
                target.update("INSERT INTO settlement_journal (journal_id, source_order_id, source_attempt_id, seller_id, "
                                + "currency, gross_amount_won, recorded_at) VALUES (?, ?, ?, ?, 'KRW', 100, CURRENT_TIMESTAMP)",
                        UUID.randomUUID(), orderId, UUID.randomUUID(), sellerId);
            }
            case "settlement_entry" -> {
                UUID orderId = UUID.randomUUID();
                UUID sellerId = UUID.randomUUID();
                UUID paymentId = UUID.randomUUID();
                UUID journalId = UUID.randomUUID();
                target.update("INSERT INTO seller (seller_id) VALUES (?)", sellerId);
                target.update("INSERT INTO purchase_order (order_id, buyer_ref, seller_id, state, checkout_expires_at, "
                                + "created_at, updated_at, order_source) "
                                + "VALUES (?, 'buyer', ?, 'PAID', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'CART')",
                        orderId, sellerId);
                target.update("INSERT INTO settlement_journal (journal_id, source_order_id, source_attempt_id, seller_id, "
                                + "currency, gross_amount_won, recorded_at) VALUES (?, ?, ?, ?, 'KRW', 100, CURRENT_TIMESTAMP)",
                        journalId, orderId, paymentId, sellerId);
                target.update("INSERT INTO settlement_entry (journal_id, account_code, entry_side, amount_won, recorded_at) "
                                + "VALUES (?, 'PSP_CLEARING', 'DEBIT', 100, CURRENT_TIMESTAMP)", journalId);
            }
            default -> throw new IllegalArgumentException("Unexpected V14 guard table: " + table);
        }
    }

    private void seedLegacyRow(JdbcTemplate target, String table) {
        UUID campaignId = UUID.randomUUID();
        if (table.equals("campaign") || table.equals("admission_counter") || table.equals("admission_ticket")
                || table.equals("inventory_reservation")) {
            target.update("INSERT INTO campaign (campaign_id, sku_id, status, queue_ticket_ttl_seconds, "
                    + "max_active_checkouts, created_at) VALUES (?, 'sku', 'OPEN', 300, 1, CURRENT_TIMESTAMP)",
                    campaignId);
        }
        switch (table) {
            case "campaign" -> { }
            case "admission_counter" -> target.update(
                    "INSERT INTO admission_counter (campaign_id, last_sequence) VALUES (?, 0)", campaignId);
            case "admission_ticket" -> target.update(
                    "INSERT INTO admission_ticket (ticket_id, campaign_id, sku_id, buyer_ref, idempotency_key, "
                            + "sequence, status, created_at, checkout_slot_held) "
                            + "VALUES (?, ?, 'sku', 'buyer', ?, 1, 'WAITING', CURRENT_TIMESTAMP, FALSE)",
                    UUID.randomUUID(), campaignId, UUID.randomUUID().toString());
            case "inventory_pool" -> target.update(
                    "INSERT INTO inventory_pool (campaign_id, sku_id, initial_capacity, available, reserved, claimed) "
                            + "VALUES (?, 'sku', 1, 1, 0, 0)", UUID.randomUUID());
            case "inventory_reservation" -> {
                target.update("INSERT INTO inventory_pool (campaign_id, sku_id, initial_capacity, available, reserved, claimed) "
                        + "VALUES (?, 'sku', 1, 1, 0, 0)", campaignId);
                target.update("INSERT INTO inventory_reservation (order_id, campaign_id, sku_id, quantity, status) "
                        + "VALUES (?, ?, 'sku', 1, 'RESERVED')", UUID.randomUUID(), campaignId);
            }
            case "purchase_order" -> target.update(
                    "INSERT INTO purchase_order (order_id, campaign_id, ticket_id, buyer_ref, sku_id, state, "
                            + "checkout_expires_at, created_at, updated_at) "
                            + "VALUES (?, ?, ?, 'buyer', 'sku', 'RESERVED', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)",
                    UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
            case "payment_attempt" -> target.update(
                    "INSERT INTO payment_attempt (order_id, attempt_id, state, payment_phase, authorization_active) "
                            + "VALUES (?, ?, 'NOT_STARTED', 'AUTHORIZATION', FALSE)", UUID.randomUUID(), UUID.randomUUID());
            default -> throw new IllegalArgumentException("Unexpected legacy table: " + table);
        }
    }

    private String createSchema() {
        String newSchema = "seller_sku_test_" + UUID.randomUUID().toString().replace("-", "");
        JdbcTemplate admin = new JdbcTemplate(new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword()));
        admin.execute("CREATE SCHEMA " + newSchema);
        return newSchema;
    }

    private JdbcTemplate jdbcForSchema(String targetSchema) {
        return new JdbcTemplate(new DriverManagerDataSource(
                POSTGRES.getJdbcUrl() + (POSTGRES.getJdbcUrl().contains("?") ? "&" : "?") + "currentSchema=" + targetSchema,
                POSTGRES.getUsername(), POSTGRES.getPassword()));
    }

    private boolean tableExists(JdbcTemplate target, String table) {
        return Boolean.TRUE.equals(target.queryForObject("SELECT to_regclass(?) IS NOT NULL", Boolean.class, table));
    }

    private boolean columnExists(JdbcTemplate target, String table, String column) {
        return target.queryForObject("SELECT COUNT(*) FROM information_schema.columns "
                        + "WHERE table_schema = current_schema() AND table_name = ? AND column_name = ?",
                Integer.class, table, column) > 0;
    }

    private Flyway flyway(String targetSchema, MigrationVersion target) {
        var configuration = Flyway.configure()
                .dataSource(jdbcForSchema(targetSchema).getDataSource())
                .schemas(targetSchema)
                .defaultSchema(targetSchema)
                .locations("classpath:db/migration");
        if (target != null) configuration.target(target);
        return configuration.load();
    }
}
