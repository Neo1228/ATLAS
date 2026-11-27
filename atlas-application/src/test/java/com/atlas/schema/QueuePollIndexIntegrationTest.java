package com.atlas.schema;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * EXP-112·ADR-0101: 1초마다 폴링하는 질의 두 개가 완료된 이력 행 수에 비례해 읽지 않는다.
 * V8 인덱스가 없으면 둘 다 표 전체를 순차 스캔하므로(인덱스를 지운 변이에서 이 시험이 실패한다) 계획에 인덱스가 들어갔는지를 본다.
 * 질의 문장은 {@code PaymentRecoveryRepository.resumeExpiredApprovalWaits}와
 * {@code JdbcPayoutDispatchStore.claim}의 후보 선택 부분과 같다.
 */
@Testcontainers
class QueuePollIndexIntegrationTest {
    private static final int HISTORY_ROWS = 30_000;

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18.6");

    private JdbcTemplate jdbc;

    @BeforeEach
    void migrateAndSeedHistory() {
        String schema = "queue_" + UUID.randomUUID().toString().replace("-", "");
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
        jdbc.execute("ALTER TABLE settlement_payout_dispatch_outbox "
                + "DROP CONSTRAINT settlement_payout_dispatch_outbox_payout_id_fkey");
        jdbc.update("""
                INSERT INTO payment_attempt (attempt_id, state, payment_phase, authorization_active, order_id)
                SELECT gen_random_uuid(), 'CAPTURED', 'CAPTURE', false, gen_random_uuid()
                FROM generate_series(1, ?)
                """, HISTORY_ROWS);
        jdbc.execute("""
                INSERT INTO payment_recovery_job (recovery_job_id, payment_attempt_id, operation_type, status,
                    available_at, first_enqueued_at, retry_window_started_at, attempt_count, work_stage, updated_at)
                SELECT gen_random_uuid(), attempt_id, 'PAYMENT_ATTEMPT', 'COMPLETED', now(), now(), now(), 1, 'APPLY', now()
                FROM payment_attempt
                """);
        jdbc.update("""
                INSERT INTO settlement_payout_dispatch_outbox (payout_id, profile_version, recipient_email_ciphertext,
                    recipient_country_code, payout_currency, requested_amount_won, provider_amount_minor,
                    fx_rate_won_per_usd, fx_source, fx_as_of_date, provider_debit_book_value_won,
                    payout_fx_rounding_expense_won, sender_batch_id, sender_item_id, paypal_request_id,
                    request_payload_sha256, status, available_at, first_enqueued_at, created_at, updated_at,
                    provider_batch_id, provider_batch_status, provider_item_id, provider_item_status)
                SELECT gen_random_uuid(), 1, 'c', 'US', 'USD', 13000, 1000, 1300.0, 'TEST', CURRENT_DATE, 13000, 0,
                    'B-' || g, 'I-' || g, 'R-' || g, md5(g::text) || md5((g + 1)::text), 'COMPLETED',
                    now(), now(), now(), now(), 'PB-' || g, 'SUCCESS', 'PI-' || g, 'SUCCESS'
                FROM generate_series(1, ?) g
                """, HISTORY_ROWS);
        jdbc.execute("ANALYZE");
    }

    @Test
    void approvalWaitResumeReadsTheWaitingIndexNotTheWholeHistory() {
        assertThat(plan("""
                WITH expired AS (
                    SELECT work.recovery_job_id
                    FROM payment_recovery_job AS work
                    JOIN payment_attempt AS attempt ON attempt.attempt_id = work.payment_attempt_id
                    WHERE work.status = 'WAITING_FOR_BUYER' AND work.work_stage = 'PROVIDER'
                      AND (attempt.approval_expires_at IS NULL OR attempt.approval_expires_at <= CURRENT_TIMESTAMP)
                    ORDER BY attempt.approval_expires_at NULLS FIRST, work.recovery_job_id
                    LIMIT 10
                    FOR UPDATE OF work SKIP LOCKED
                )
                SELECT * FROM expired
                """)).contains("ix_payment_recovery_job_waiting_for_buyer");
    }

    @Test
    void payoutClaimReadsTheLeasedIndexNotTheWholeHistory() {
        assertThat(plan("""
                WITH candidates AS (
                    SELECT o.payout_id FROM settlement_payout_dispatch_outbox o
                    WHERE ((o.status IN ('PENDING', 'WAITING_PROVIDER') AND o.available_at <= now())
                       OR (o.status = 'LEASED' AND o.lease_until <= now()))
                    ORDER BY o.available_at, o.first_enqueued_at, o.payout_id
                    LIMIT 10
                    FOR UPDATE OF o SKIP LOCKED
                )
                SELECT * FROM candidates
                """)).contains("ix_settlement_payout_dispatch_leased")
                .doesNotContain("Seq Scan on settlement_payout_dispatch_outbox");
    }

    private String plan(String sql) {
        List<String> lines = jdbc.queryForList("EXPLAIN " + sql, String.class);
        return String.join("\n", lines);
    }
}
