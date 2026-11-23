package com.atlas.schema;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@Testcontainers(disabledWithoutDocker = true)
class PayPalBalanceReportSchemaIntegrationTest {

    @Container
    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18.6");

    private JdbcTemplate jdbc;

    @BeforeEach
    void setUp() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("DROP SCHEMA public CASCADE");
        jdbc.execute("CREATE SCHEMA public");
        jdbc.execute(BaselineSchema.script());
    }

    @Test
    void createsRunRowsAndAppendOnlyDispositionTables() {
        UUID runId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO paypal_balance_report_run
                    (run_id, report_sha256, report_format, row_count, matched_count,
                     unmatched_count, mismatch_count, duplicate_count)
                VALUES (?, ?, 'CSV', 1, 0, 1, 0, 0)
                """, runId, "a".repeat(64));
        jdbc.update("""
                INSERT INTO paypal_balance_report_row
                    (run_id, row_number, record_id, record_type, currency, gross_amount,
                     fee_amount, net_amount, status, reason_code)
                VALUES (?, 1, 'record-1', 'SALE', 'USD', 10.00, 1.00, 9.00,
                        'UNMATCHED', 'NO_EXACT_MATCH')
                """, runId);
        jdbc.update("""
                INSERT INTO paypal_balance_report_disposition
                    (disposition_id, run_id, row_number, disposition, actor, reason, idempotency_key)
                VALUES (?, ?, 1, 'DEFERRED', 'operator', 'awaiting external evidence', 'key-1')
                """, UUID.randomUUID(), runId);

        assertThat(jdbc.queryForObject("SELECT count(*) FROM paypal_balance_report_run", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM paypal_balance_report_row", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM paypal_balance_report_disposition", Integer.class))
                .isEqualTo(1);
        assertThatThrownBy(() -> jdbc.update("UPDATE paypal_balance_report_row SET status = 'MATCHED' "
                + "WHERE run_id = ? AND row_number = 1", runId)).isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> jdbc.update("DELETE FROM paypal_balance_report_disposition WHERE idempotency_key = ?",
                "key-1")).isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> jdbc.execute("TRUNCATE paypal_balance_report_disposition"))
                .isInstanceOf(DataAccessException.class);
    }

    @Test
    void rejectsInconsistentCountsAndDispositionWithoutAReportRow() {
        UUID runId = UUID.randomUUID();
        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO paypal_balance_report_run
                    (run_id, report_sha256, report_format, row_count, matched_count,
                     unmatched_count, mismatch_count, duplicate_count)
                VALUES (?, ?, 'CSV', 2, 0, 1, 0, 0)
                """, runId, "b".repeat(64))).isInstanceOf(DataAccessException.class);

        jdbc.update("""
                INSERT INTO paypal_balance_report_run
                    (run_id, report_sha256, report_format, row_count, matched_count,
                     unmatched_count, mismatch_count, duplicate_count)
                VALUES (?, ?, 'CSV', 1, 0, 1, 0, 0)
                """, runId, "c".repeat(64));
        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO paypal_balance_report_disposition
                    (disposition_id, run_id, row_number, disposition, actor, reason, idempotency_key)
                VALUES (?, ?, 1, 'DEFERRED', 'operator', 'reason', 'key-missing-row')
                """, UUID.randomUUID(), runId)).isInstanceOf(DataAccessException.class);
    }
}
