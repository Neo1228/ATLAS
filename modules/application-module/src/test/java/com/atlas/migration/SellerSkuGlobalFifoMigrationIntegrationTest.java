package com.atlas.migration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Timestamp;
import java.time.Instant;
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
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18.6")
            .withDatabaseName("atlas")
            .withUsername("atlas")
            .withPassword("atlas");

    private JdbcTemplate jdbc;

    @BeforeEach
    void migrateLegacySchema() {
        flyway(null).clean();
        flyway(MigrationVersion.fromVersion("5")).migrate();
        jdbc = new JdbcTemplate(new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword()));
    }

    @Test
    void mapsCampaignsAndAggregatesInventoryCapacityGlobalKAndQueueSequence() {
        UUID sellerId = UUID.randomUUID();
        UUID campaignA = UUID.randomUUID();
        UUID campaignB = UUID.randomUUID();
        String skuId = "shared-sku";
        Instant createdA = Instant.parse("2026-01-01T00:00:00Z");
        Instant createdB = Instant.parse("2026-01-01T00:00:01Z");
        UUID orderA = UUID.randomUUID();
        UUID orderB = UUID.randomUUID();
        UUID ticketA = UUID.randomUUID();
        UUID ticketAWaiting = UUID.randomUUID();
        UUID ticketB = UUID.randomUUID();
        Instant expiry = Instant.parse("2027-01-01T00:10:00Z");

        insertCampaign(campaignA, skuId, 2, createdA);
        insertCampaign(campaignB, skuId, 3, createdB);
        jdbc.update("INSERT INTO inventory_pool (campaign_id, sku_id, initial_capacity, available, reserved, claimed) "
                + "VALUES (?, ?, 5, 4, 1, 0)", campaignA, skuId);
        jdbc.update("INSERT INTO inventory_pool (campaign_id, sku_id, initial_capacity, available, reserved, claimed) "
                + "VALUES (?, ?, 7, 6, 0, 1)", campaignB, skuId);
        jdbc.update("INSERT INTO inventory_reservation (order_id, campaign_id, sku_id, quantity, status) "
                + "VALUES (?, ?, ?, 1, 'RESERVED')", orderA, campaignA, skuId);
        jdbc.update("INSERT INTO inventory_reservation (order_id, campaign_id, sku_id, quantity, status) "
                + "VALUES (?, ?, ?, 1, 'CLAIMED')", orderB, campaignB, skuId);
        insertOrder(orderA, campaignA, ticketA, skuId, "RESERVED", createdA, expiry);
        insertOrder(orderB, campaignB, ticketB, skuId, "PAID", createdB, expiry);
        jdbc.update("INSERT INTO admission_counter (campaign_id, last_sequence) VALUES (?, 2)", campaignA);
        jdbc.update("INSERT INTO admission_counter (campaign_id, last_sequence) VALUES (?, 1)", campaignB);
        insertTicket(ticketA, campaignA, skuId, "buyer-a", "key-a", 1, "CHECKOUT_ACTIVE", expiry, createdA, true);
        insertTicket(ticketAWaiting, campaignA, skuId, "buyer-a2", "key-a2", 2, "WAITING", expiry, createdB, false);
        insertTicket(ticketB, campaignB, skuId, "buyer-b", "key-b", 1, "WAITING", expiry,
                createdB.plusSeconds(1), false);
        jdbc.update("CREATE TABLE campaign_seller_backfill (campaign_id UUID PRIMARY KEY, seller_id UUID NOT NULL)");
        jdbc.update("INSERT INTO campaign_seller_backfill (campaign_id, seller_id) VALUES (?, ?)", campaignA, sellerId);
        jdbc.update("INSERT INTO campaign_seller_backfill (campaign_id, seller_id) VALUES (?, ?)", campaignB, sellerId);

        flyway(null).migrate();

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM seller", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT max_active_checkouts FROM seller_sku WHERE seller_id = ? AND sku_id = ?",
                Integer.class, sellerId, skuId)).isEqualTo(5);
        assertThat(jdbc.queryForObject(
                "SELECT initial_capacity FROM inventory_pool WHERE seller_id = ? AND sku_id = ?",
                Integer.class, sellerId, skuId)).isEqualTo(12);
        assertThat(jdbc.queryForObject(
                "SELECT available FROM inventory_pool WHERE seller_id = ? AND sku_id = ?",
                Integer.class, sellerId, skuId)).isEqualTo(10);
        assertThat(jdbc.queryForObject(
                "SELECT reserved FROM inventory_pool WHERE seller_id = ? AND sku_id = ?",
                Integer.class, sellerId, skuId)).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT claimed FROM inventory_pool WHERE seller_id = ? AND sku_id = ?",
                Integer.class, sellerId, skuId)).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM inventory_reservation WHERE seller_id = ? AND sku_id = ?",
                Integer.class, sellerId, skuId)).isEqualTo(2);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM purchase_order WHERE seller_id = ?",
                Integer.class, sellerId)).isEqualTo(2);
        assertThat(jdbc.queryForObject(
                "SELECT last_sequence FROM admission_counter WHERE seller_id = ? AND sku_id = ?",
                Long.class, sellerId, skuId)).isEqualTo(3L);
        assertThat(jdbc.queryForList(
                "SELECT sequence FROM admission_ticket WHERE seller_id = ? AND sku_id = ? ORDER BY sequence",
                Long.class, sellerId, skuId)).containsExactly(1L, 2L, 3L);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM campaign WHERE seller_id = ?",
                Integer.class, sellerId)).isEqualTo(2);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM information_schema.columns "
                        + "WHERE table_schema = current_schema() AND table_name = 'campaign' "
                        + "AND column_name = 'max_active_checkouts'",
                Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT to_regclass('campaign_seller_backfill') IS NULL", Boolean.class)).isTrue();
    }

    @Test
    void refusesPopulatedLegacySchemaWithoutCompleteSellerMapping() {
        UUID campaignId = UUID.randomUUID();
        insertCampaign(campaignId, "unmapped-sku", 1, Instant.parse("2026-01-01T00:00:00Z"));
        jdbc.update("INSERT INTO inventory_pool (campaign_id, sku_id, initial_capacity, available, reserved, claimed) "
                + "VALUES (?, ?, 1, 1, 0, 0)", campaignId, "unmapped-sku");

        assertThatThrownBy(() -> flyway(null).migrate()).isInstanceOf(FlywayException.class);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM campaign WHERE campaign_id = ?", Integer.class, campaignId))
                .isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM information_schema.columns "
                        + "WHERE table_schema = current_schema() AND table_name = 'campaign' AND column_name = 'seller_id'",
                Integer.class)).isZero();
    }

    @Test
    void rejectsGlobalCheckoutLimitOverflowBeforeChangingLegacySchema() {
        UUID sellerId = UUID.randomUUID();
        UUID campaignA = UUID.randomUUID();
        UUID campaignB = UUID.randomUUID();
        String skuId = "global-k-overflow";
        Instant createdAt = Instant.parse("2026-01-01T00:00:00Z");
        insertCampaign(campaignA, skuId, Integer.MAX_VALUE, createdAt);
        insertCampaign(campaignB, skuId, 1, createdAt.plusSeconds(1));
        insertZeroInventoryPool(campaignA, skuId);
        insertZeroInventoryPool(campaignB, skuId);
        insertBackfill(campaignA, sellerId);
        insertBackfill(campaignB, sellerId);

        assertThatThrownBy(() -> flyway(null).migrate())
                .isInstanceOf(FlywayException.class)
                .hasStackTraceContaining("Global Seller-SKU checkout limit exceeds INTEGER");
        assertNoSellerSchemaChanges(campaignA, campaignB);
    }

    @Test
    void rejectsInitialStockOverflowBeforeChangingLegacySchema() {
        UUID sellerId = UUID.randomUUID();
        UUID campaignA = UUID.randomUUID();
        UUID campaignB = UUID.randomUUID();
        String skuId = "stock-overflow";
        Instant createdAt = Instant.parse("2026-01-01T00:00:00Z");
        insertCampaign(campaignA, skuId, 1, createdAt);
        insertCampaign(campaignB, skuId, 1, createdAt.plusSeconds(1));
        insertInventoryPool(campaignA, skuId, Integer.MAX_VALUE);
        insertInventoryPool(campaignB, skuId, Integer.MAX_VALUE);
        insertBackfill(campaignA, sellerId);
        insertBackfill(campaignB, sellerId);

        assertThatThrownBy(() -> flyway(null).migrate())
                .isInstanceOf(FlywayException.class)
                .hasStackTraceContaining("Global Seller-SKU stock exceeds INTEGER");
        assertNoSellerSchemaChanges(campaignA, campaignB);
    }

    private void insertZeroInventoryPool(UUID campaignId, String skuId) {
        jdbc.update("INSERT INTO inventory_pool (campaign_id, sku_id, initial_capacity, available, reserved, claimed) "
                + "VALUES (?, ?, 0, 0, 0, 0)", campaignId, skuId);
    }

    private void insertInventoryPool(UUID campaignId, String skuId, int capacity) {
        jdbc.update("INSERT INTO inventory_pool (campaign_id, sku_id, initial_capacity, available, reserved, claimed) "
                + "VALUES (?, ?, ?, ?, 0, 0)", campaignId, skuId, capacity, capacity);
    }

    private void insertBackfill(UUID campaignId, UUID sellerId) {
        jdbc.update("CREATE TABLE IF NOT EXISTS campaign_seller_backfill "
                + "(campaign_id UUID PRIMARY KEY, seller_id UUID NOT NULL)");
        jdbc.update("INSERT INTO campaign_seller_backfill (campaign_id, seller_id) VALUES (?, ?)",
                campaignId, sellerId);
    }

    private void assertNoSellerSchemaChanges(UUID campaignA, UUID campaignB) {
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM campaign WHERE campaign_id IN (?, ?)",
                Integer.class, campaignA, campaignB)).isEqualTo(2);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = current_schema() "
                        + "AND table_name = 'campaign' AND column_name = 'seller_id'",
                Integer.class)).isZero();
    }

    private void insertCampaign(UUID campaignId, String skuId, int activeLimit, Instant createdAt) {
        jdbc.update("INSERT INTO campaign (campaign_id, sku_id, status, queue_ticket_ttl_seconds, "
                        + "max_active_checkouts, created_at) VALUES (?, ?, 'OPEN', 300, ?, ?)",
                campaignId, skuId, activeLimit, Timestamp.from(createdAt));
    }

    private void insertOrder(UUID orderId, UUID campaignId, UUID ticketId, String skuId, String state,
                             Instant createdAt, Instant expiresAt) {
        jdbc.update("INSERT INTO purchase_order (order_id, campaign_id, ticket_id, buyer_ref, sku_id, state, "
                        + "checkout_expires_at, payment_attempt_id, created_at, updated_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, NULL, ?, ?)",
                orderId, campaignId, ticketId, "migration-buyer", skuId, state, Timestamp.from(expiresAt),
                Timestamp.from(createdAt), Timestamp.from(createdAt));
    }

    private void insertTicket(UUID ticketId, UUID campaignId, String skuId, String buyerRef, String key, long sequence,
                              String status, Instant expiresAt, Instant createdAt, boolean slotHeld) {
        jdbc.update("INSERT INTO admission_ticket (ticket_id, campaign_id, sku_id, buyer_ref, idempotency_key, "
                        + "sequence, status, expires_at, created_at, checkout_slot_acquired_at, checkout_slot_held) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                ticketId, campaignId, skuId, buyerRef, key, sequence, status, Timestamp.from(expiresAt),
                Timestamp.from(createdAt), slotHeld ? Timestamp.from(createdAt) : null, slotHeld);
    }

    private Flyway flyway(MigrationVersion target) {
        var configuration = Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("classpath:db/migration")
                .cleanDisabled(false);
        if (target != null) configuration.target(target);
        return configuration.load();
    }
}
