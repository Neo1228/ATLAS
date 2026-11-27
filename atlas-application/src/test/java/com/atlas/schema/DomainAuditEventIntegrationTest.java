package com.atlas.schema;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.atlas.account.model.AccountSuspensionActionType;
import com.atlas.account.repository.AccountSuspensionRepository;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
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
 * 통합 감사·도메인 이벤트 로그(V9 {@code domain_audit_event}, ADR-0102, EXP-113 H14·H15·H18)를 실제 PostgreSQL로 확인한다.
 *
 * <p>로그는 원본 테이블의 AFTER INSERT 트리거가 같은 transaction에서 쓴다. 쓰기 코드가 바뀌어도 로그가 빠지지 않는지는
 * 트리거 존재로 지키므로, 대상 테이블 6개 모두에 트리거가 걸려 있는지와 실제 저장소 코드로 쓴 조치가 로그에 남는지를 함께 본다.
 * 재고 조정·이동은 판매자 쓰기 경로의 비용 때문에 대상에서 뺐다(EXP-113 H21). 목록이 정확히 같아야 하므로 다시 넣으면 실패한다.
 */
@Testcontainers
class DomainAuditEventIntegrationTest {
    private static final List<String> AUDITED_TABLES = List.of(
            "account_suspension_action", "payment_recovery_operator_action", "paypal_webhook_retry_operator_audit",
            "seller_payout_profile_action", "settlement_payout_operator_action", "paypal_balance_report_disposition");

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18.6").withLabel("atlasexp", "t1");

    private JdbcTemplate jdbc;
    private TransactionTemplate transactions;

    @BeforeEach
    void migrateFreshSchema() {
        String schema = "audit_" + UUID.randomUUID().toString().replace("-", "");
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
    }

    @Test
    void operatorActionIsLoggedOnceInTheSameTransactionWithoutFreeTextOrKeys() {
        UUID accountId = jdbc.queryForObject(
                "INSERT INTO user_account(email, password_hash) VALUES (?, 'hash') RETURNING account_id",
                UUID.class, "audit-" + UUID.randomUUID() + "@example.com");
        AccountSuspensionRepository suspensions = new AccountSuspensionRepository(jdbc);

        // 실제 저장소 코드로 정지 조치를 쓰고 롤백하면 로그도 남지 않는다.
        transactions.executeWithoutResult(status -> {
            suspensions.apply(accountId, AccountSuspensionActionType.SUSPEND, "rolled-back-key", "operator-7",
                    "contains a phone number 010-0000-0000", Instant.parse("2026-11-27T00:00:00Z"));
            status.setRollbackOnly();
        });
        assertThat(auditRows("account", accountId.toString())).isEmpty();

        transactions.executeWithoutResult(status -> suspensions.apply(accountId, AccountSuspensionActionType.SUSPEND,
                "kept-key", "operator-7", "contains a phone number 010-0000-0000",
                Instant.parse("2026-11-27T00:00:01Z")));

        List<Map<String, Object>> rows = auditRows("account", accountId.toString());
        assertThat(rows).hasSize(1);
        Map<String, Object> row = rows.get(0);
        assertThat(row).containsEntry("source_table", "account_suspension_action")
                .containsEntry("event_type", "ACCOUNT_SUSPEND").containsEntry("actor", "operator-7");
        assertThat(((Timestamp) row.get("occurred_at")).toInstant()).isEqualTo(Instant.parse("2026-11-27T00:00:01Z"));
        String payload = row.get("payload").toString();
        assertThat(payload).contains(accountId.toString())
                .doesNotContain("reason", "010-0000-0000", "idempotency_key", "kept-key");
    }

    @Test
    void everyAuditedTableWritesTheLogAndTheLogIsAppendOnly() {
        List<String> tablesWithAuditTrigger = jdbc.queryForList("""
                SELECT c.relname FROM pg_trigger t
                JOIN pg_class c ON c.oid = t.tgrelid
                JOIN pg_namespace n ON n.oid = c.relnamespace
                JOIN pg_proc p ON p.oid = t.tgfoid
                WHERE n.nspname = current_schema() AND p.proname = 'record_domain_audit_event' AND t.tgenabled = 'O'
                """, String.class);
        assertThat(tablesWithAuditTrigger).containsExactlyInAnyOrderElementsOf(AUDITED_TABLES);

        jdbc.update("""
                INSERT INTO domain_audit_event(occurred_at, source_table, source_id, event_type, aggregate_type,
                                               aggregate_id, payload)
                VALUES (now(), 'manual', 'x', 'PROBE', 'probe', 'x', '{}')
                """);
        assertThatThrownBy(() -> jdbc.update("UPDATE domain_audit_event SET actor = 'forged'"))
                .hasMessageContaining("domain_audit_event_is_append-only");
        assertThatThrownBy(() -> jdbc.update("DELETE FROM domain_audit_event"))
                .hasMessageContaining("domain_audit_event_is_append-only");
        assertThatThrownBy(() -> jdbc.execute("TRUNCATE domain_audit_event"))
                .hasMessageContaining("domain_audit_event_is_append-only");
    }

    private List<Map<String, Object>> auditRows(String aggregateType, String aggregateId) {
        return jdbc.queryForList("""
                SELECT source_table, event_type, actor, occurred_at, payload::text AS payload
                FROM domain_audit_event WHERE aggregate_type = ? AND aggregate_id = ?
                """, aggregateType, aggregateId);
    }
}
