package com.atlas.schema;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * V11 가드(EXP-116, ADR-0105): 문서가 추가 전용이라고 쓰는 감사·이벤트 테이블이 UPDATE·DELETE·TRUNCATE를
 * SQLSTATE P0001로 거절하는지, 문서의 롤백 스크립트가 V11이 더한 것만 지우는지 실제 PostgreSQL로 확인한다.
 *
 * <p>각 테이블에 행이 있어야 행 트리거가 발동하므로 외래 키 부모 없이 한 행씩 넣는다. 그 INSERT만
 * {@code session_replication_role = replica}로 외래 키와 INSERT 트리거(V5 분리, V9 감사 로그)를 건너뛴다.
 * 거절 확인은 기본 모드의 슈퍼유저 연결에서 한다.
 */
@Testcontainers
class AuditAppendOnlyGuardIntegrationTest {
    private static final String RAISE_EXCEPTION = "P0001";
    private static final Path ROLLBACK_SCRIPT = Path.of("..", "scripts", "db", "rollback",
            "V11__undo_audit_append_only_guards.sql").toAbsolutePath().normalize();

    /** 테이블 → 그 테이블에 한 행을 넣는 INSERT. V11이 가드를 더한 8개(TRUNCATE만 더한 2개 포함)다. */
    private static final Map<String, String> SEED = new LinkedHashMap<>();

    static {
        SEED.put("inventory_capacity_adjustment", """
                INSERT INTO inventory_capacity_adjustment (seller_id, sku_id, idempotency_key, delta, reason,
                    initial_capacity, available, reserved, claimed, location_id)
                VALUES (gen_random_uuid(), 'sku-1', 'k', 1, 'restock', 1, 1, 0, 0, gen_random_uuid())
                """);
        SEED.put("account_suspension_action", """
                INSERT INTO account_suspension_action (suspension_action_id, account_id, action, idempotency_key,
                    actor, reason, created_at)
                VALUES (gen_random_uuid(), gen_random_uuid(), 'SUSPEND', 'k', 'operator', 'abuse', now())
                """);
        SEED.put("payment_recovery_operator_action", """
                INSERT INTO payment_recovery_operator_action (operator_action_id, recovery_job_id, idempotency_key,
                    actor, reason, created_at)
                VALUES (gen_random_uuid(), gen_random_uuid(), 'k', 'operator', 'retry', now())
                """);
        SEED.put("seller_payout_profile_action", """
                INSERT INTO seller_payout_profile_action (seller_id, profile_version, action, idempotency_key,
                    request_facts_sha256, actor, reason, created_at)
                VALUES (gen_random_uuid(), 1, 'PROFILE_SUBMITTED', 'k', repeat('a', 64), 'operator', 'submit', now())
                """);
        SEED.put("settlement_payout_operator_action", """
                INSERT INTO settlement_payout_operator_action (payout_id, idempotency_key, action, actor, reason,
                    created_at)
                VALUES (gen_random_uuid(), 'k', 'PAYOUT_CANCELLED', 'operator', 'cancel', now())
                """);
        SEED.put("order_inventory_event", """
                INSERT INTO order_inventory_event (workflow_id, order_id, aggregate_version, event_type)
                VALUES (gen_random_uuid(), gen_random_uuid(), 1, 'RESERVATION_PENDING')
                """);
        SEED.put("inventory_transfer", """
                INSERT INTO inventory_transfer (seller_id, sku_id, source_location_id, destination_location_id,
                    idempotency_key, quantity, reason)
                VALUES (gen_random_uuid(), 'sku-1', gen_random_uuid(), gen_random_uuid(), 'k', 1, 'move')
                """);
        SEED.put("paypal_webhook_retry_operator_audit", """
                INSERT INTO paypal_webhook_retry_operator_audit (idempotency_key, event_id, actor, reason, outcome)
                VALUES ('k', 'WH-1', 'operator', 'requeue', 'REQUEUED')
                """);
    }

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18.6");

    private JdbcTemplate jdbc;
    private TransactionTemplate transactions;

    @BeforeEach
    void migrateFreshSchemaWithOneRowPerGuardedTable() {
        String schema = "guard_" + UUID.randomUUID().toString().replace("-", "");
        new JdbcTemplate(new SingleConnectionDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword(), true))
                .execute("CREATE SCHEMA " + schema);
        Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .schemas(schema).defaultSchema(schema).locations("classpath:db/migration")
                .load().migrate();
        String url = POSTGRES.getJdbcUrl() + (POSTGRES.getJdbcUrl().contains("?") ? "&" : "?")
                + "currentSchema=" + schema;
        SingleConnectionDataSource dataSource = new SingleConnectionDataSource(
                url, POSTGRES.getUsername(), POSTGRES.getPassword(), true);
        jdbc = new JdbcTemplate(dataSource);
        transactions = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        jdbc.execute("SET session_replication_role = replica");
        try {
            SEED.values().forEach(jdbc::update);
        } finally {
            jdbc.execute("SET session_replication_role = DEFAULT");
        }
    }

    @Test
    void everyGuardedTableRejectsUpdateDeleteAndTruncate() {
        for (String table : SEED.keySet()) {
            String timestamp = table.equals("order_inventory_event") ? "occurred_at" : "created_at";
            for (String statement : new String[] {
                    "UPDATE " + table + " SET " + timestamp + " = " + timestamp,
                    "DELETE FROM " + table,
                    "TRUNCATE " + table}) {
                assertThatThrownBy(() -> jdbc.execute(statement))
                        .as(statement)
                        .satisfies(error -> assertThat(sqlState(error)).as(statement).isEqualTo(RAISE_EXCEPTION));
            }
            assertThat(count(table)).as(table).isEqualTo(1L);
        }
        // V11이 새로 가드한 6개는 테이블 이름을 메시지에 싣는다(V7의 settlement_ledger_is_append-only와 같은 꼴).
        assertThatThrownBy(() -> jdbc.execute("DELETE FROM inventory_capacity_adjustment"))
                .hasMessageContaining("inventory_capacity_adjustment_is_append-only");
    }

    @Test
    void documentedRollbackScriptRemovesOnlyTheV11Guards() throws IOException {
        String undo = Files.readString(ROLLBACK_SCRIPT);
        transactions.executeWithoutResult(status -> {
            jdbc.execute(undo);
            for (String table : SEED.keySet()) {
                if (table.equals("inventory_transfer") || table.equals("paypal_webhook_retry_operator_audit")) {
                    // V1의 행 가드는 V11 이전부터 있으므로 롤백 뒤에도 남는다.
                    jdbc.execute("SAVEPOINT probe");
                    assertThatThrownBy(() -> jdbc.execute("DELETE FROM " + table))
                            .as(table)
                            .satisfies(error -> assertThat(sqlState(error)).isEqualTo(RAISE_EXCEPTION));
                    jdbc.execute("ROLLBACK TO SAVEPOINT probe");
                } else {
                    assertThat(jdbc.update("DELETE FROM " + table)).as(table).isEqualTo(1);
                }
            }
            jdbc.execute("TRUNCATE inventory_transfer, paypal_webhook_retry_operator_audit");
            status.setRollbackOnly();
        });
        // 되돌렸으므로 가드와 행이 그대로다.
        assertThat(count("inventory_capacity_adjustment")).isEqualTo(1L);
        assertThatThrownBy(() -> jdbc.execute("DELETE FROM inventory_capacity_adjustment"))
                .satisfies(error -> assertThat(sqlState(error)).isEqualTo(RAISE_EXCEPTION));
    }

    private long count(String table) {
        return jdbc.queryForObject("SELECT count(*) FROM " + table, Long.class);
    }

    private static String sqlState(Throwable error) {
        for (Throwable cause = error; cause != null; cause = cause.getCause()) {
            if (cause instanceof SQLException sql) {
                return sql.getSQLState();
            }
        }
        return null;
    }
}
