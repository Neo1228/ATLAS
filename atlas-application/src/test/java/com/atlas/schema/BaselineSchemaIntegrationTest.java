package com.atlas.schema;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * 기준 스키마(V1__baseline.sql)를 Flyway로 빈 스키마에 적용한 최종 결과의 성질을 확인한다.
 * 이전 상태에서 올리는 업그레이드는 검증하지 않는다. 아직 배포하지 않아 올려야 할 데이터가 없다.
 */
@Testcontainers
class BaselineSchemaIntegrationTest {
    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18.6");

    private String schema;
    private JdbcTemplate jdbc;

    @BeforeEach
    void migrateFreshSchema() {
        schema = "final_schema_" + UUID.randomUUID().toString().replace("-", "");
        new JdbcTemplate(new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword()))
                .execute("CREATE SCHEMA " + schema);
        String url = POSTGRES.getJdbcUrl() + (POSTGRES.getJdbcUrl().contains("?") ? "&" : "?")
                + "currentSchema=" + schema;
        jdbc = new JdbcTemplate(new DriverManagerDataSource(url, POSTGRES.getUsername(), POSTGRES.getPassword()));
        Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .schemas(schema)
                .defaultSchema(schema)
                .locations("classpath:db/migration")
                .load()
                .migrate();
    }

    @Test
    void baselineCreatesEveryApplicationTableAndKeyColumn() {
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
        assertThat(tableExists(jdbc, "order_shipment")).isTrue();
        assertThat(tableExists(jdbc, "order_shipment_item")).isTrue();
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
        assertThat(columnExists(jdbc, "shopping_cart", "seller_id")).isFalse();
        assertThat(columnExists(jdbc, "settlement_journal", "checkout_id")).isTrue();
        assertThat(tableExists(jdbc, "campaign_seller_backfill")).isFalse();
        assertThat(tableExists(jdbc, "settlement_journal")).isTrue();
        assertThat(tableExists(jdbc, "settlement_entry")).isTrue();
        assertThat(tableExists(jdbc, "buyer_checkout")).isTrue();
        assertThat(tableExists(jdbc, "settlement_seller_allocation")).isTrue();
        assertThat(tableExists(jdbc, "order_inventory_restock")).isTrue();
        assertThat(tableExists(jdbc, "order_inventory_restock_item")).isTrue();
        assertThat(tableExists(jdbc, "order_inventory_event")).isTrue();
        assertThat(tableExists(jdbc, "seller_payout_profile")).isTrue();
        assertThat(tableExists(jdbc, "seller_payout_profile_action")).isTrue();
        assertThat(tableExists(jdbc, "settlement_refund_reservation")).isTrue();
        assertThat(tableExists(jdbc, "settlement_payout_dispatch_outbox")).isTrue();
        assertThat(tableExists(jdbc, "settlement_payout_operator_action")).isTrue();
        assertThat(jdbc.queryForObject("""
                SELECT kcu.column_name
                FROM information_schema.table_constraints tc
                JOIN information_schema.key_column_usage kcu
                  ON tc.constraint_name = kcu.constraint_name
                 AND tc.constraint_schema = kcu.constraint_schema
                WHERE tc.table_schema = ? AND tc.table_name = 'payment_attempt'
                  AND tc.constraint_type = 'PRIMARY KEY'
                """, String.class, schema)).isEqualTo("attempt_id");
        assertThat(columnExists(jdbc, "inventory_pool", "location_id")).isTrue();
        assertThat(columnExists(jdbc, "inventory_reservation", "location_id")).isTrue();
        assertThat(columnExists(jdbc, "purchase_order", "fulfillment_location_id")).isTrue();
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = ? AND table_type = 'BASE TABLE'",
                Integer.class, schema)).isEqualTo(84); // 애플리케이션 테이블 83개(V5 payout_operator_account, V9 account_notification_outbox·domain_audit_event, V10 settlement_seller_balance_checkpoint·settlement_balance_checkpoint_cursor, V14 assistant_spending_cap·assistant_conversation·assistant_message, V16 payment_provider_adjustment 포함)와 Flyway 이력 테이블
    }

    @Test
    void shipmentStatusAllowsPartiallyShippedAndIsWideEnough() {
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM information_schema.check_constraints WHERE constraint_schema = ? "
                        + "AND constraint_name = 'order_fulfillment_status' AND check_clause LIKE '%PARTIALLY_SHIPPED%'",
                Integer.class, schema)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM order_shipment", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT character_maximum_length FROM information_schema.columns "
                + "WHERE table_schema = ? AND table_name = 'order_fulfillment' AND column_name = 'status'",
                Integer.class, schema)).isEqualTo(24);
    }

    @Test
    void productMustHaveAPrimaryCategoryOfItsOwnSeller() {
        assertThat(jdbc.queryForObject("SELECT is_nullable FROM information_schema.columns "
                + "WHERE table_schema = current_schema() AND table_name = 'product' "
                + "AND column_name = 'primary_category_id'", String.class)).isEqualTo("NO");
        UUID seller = seller();
        UUID otherSeller = seller();
        UUID otherCategory = category(otherSeller, "OTHER", null);
        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO product(product_id, seller_id, name, primary_category_id) VALUES (?, ?, ?, ?)",
                UUID.randomUUID(), seller, "Mismatched", otherCategory))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void categoryParentMustBelongToTheSameSellerAndCannotBeItself() {
        UUID sellerA = seller();
        UUID sellerB = seller();
        UUID rootA = category(sellerA, "ROOT", null);
        UUID rootB = category(sellerB, "ROOT", null);
        assertThat(category(sellerA, "CHILD", rootA)).isNotNull();

        assertThatThrownBy(() -> category(sellerA, "BAD", rootB))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update(
                "UPDATE product_category SET parent_category_id = category_id WHERE category_id = ?", rootA))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void categoryAssignmentRejectsACategoryOfAnotherSeller() {
        UUID sellerA = seller();
        UUID sellerB = seller();
        UUID categoryA = category(sellerA, "A", null);
        UUID categoryB = category(sellerB, "B", null);
        UUID productA = product(sellerA, categoryA);
        UUID productB = product(sellerB, categoryB);
        UUID extraA = category(sellerA, "EXTRA", null);

        jdbc.update("INSERT INTO product_category_assignment(seller_id, product_id, category_id) VALUES (?, ?, ?)",
                sellerA, productA, extraA);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM product_category_assignment WHERE product_id = ?", Integer.class, productA))
                .isEqualTo(1);
        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO product_category_assignment(seller_id, product_id, category_id) VALUES (?, ?, ?)",
                sellerB, productB, extraA)).isInstanceOf(DataIntegrityViolationException.class);
    }

    private UUID seller() {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO seller(seller_id) VALUES (?)", id);
        return id;
    }

    private UUID category(UUID sellerId, String code, UUID parent) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO product_category(category_id, seller_id, code, name, parent_category_id) "
                + "VALUES (?, ?, ?, ?, ?)", id, sellerId, code, code, parent);
        return id;
    }

    private UUID product(UUID sellerId, UUID primaryCategory) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO product(product_id, seller_id, name, primary_category_id) VALUES (?, ?, ?, ?)",
                id, sellerId, "Product", primaryCategory);
        return id;
    }

    private boolean tableExists(JdbcTemplate target, String table) {
        return Boolean.TRUE.equals(target.queryForObject("SELECT to_regclass(?) IS NOT NULL", Boolean.class, table));
    }

    private boolean columnExists(JdbcTemplate target, String table, String column) {
        return target.queryForObject("SELECT COUNT(*) FROM information_schema.columns "
                + "WHERE table_schema = current_schema() AND table_name = ? AND column_name = ?",
                Integer.class, table, column) > 0;
    }
}
