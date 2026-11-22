package com.atlas.application.service;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * 소진({@code EXHAUSTED})된 PayPal webhook 재시도 이벤트의 운영자 조회와 수동 재큐 영속화.
 *
 * <p>트랜잭션 경계는 호출 서비스가 소유한다. 재큐 관련 메서드는 같은 트랜잭션 안에서 호출해야 한다.
 */
@Repository
public class PayPalWebhookOperatorRepository {
    private final JdbcTemplate jdbc;

    public PayPalWebhookOperatorRepository(JdbcTemplate jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc);
    }

    public long countExhausted() {
        Long total = jdbc.queryForObject(
                "SELECT COUNT(*) FROM paypal_webhook_retry WHERE state = 'EXHAUSTED'", Long.class);
        return total == null ? 0 : total;
    }

    public List<ExhaustedRetryRow> listExhausted(int limit, long offset) {
        return jdbc.query("""
                SELECT event_id, event_type, attempt_count, created_at, finished_at
                FROM paypal_webhook_retry
                WHERE state = 'EXHAUSTED'
                ORDER BY finished_at DESC, event_id ASC
                LIMIT ? OFFSET ?
                """, (rs, rowNum) -> mapExhausted(rs), limit, offset);
    }

    /** 같은 멱등 키로 이전에 기록된 요청. 재큐 대상 행이 삭제돼도 감사 기록이 남아 있으면 재생할 수 있다. */
    public Optional<RequeueRecord> findRequeueRecord(String idempotencyKey) {
        return jdbc.query("""
                SELECT event_id, actor, reason, outcome
                FROM paypal_webhook_retry_operator_audit
                WHERE idempotency_key = ?
                """, (rs, rowNum) -> new RequeueRecord(
                        rs.getString("event_id"), rs.getString("actor"),
                        rs.getString("reason"), rs.getString("outcome")), idempotencyKey)
                .stream().findFirst();
    }

    /** {@code EXHAUSTED} 행을 잠그고, 잠글 행이 있으면 {@code true}. */
    public boolean lockExhausted(String eventId) {
        return !jdbc.query("""
                SELECT event_id
                FROM paypal_webhook_retry
                WHERE event_id = ? AND state = 'EXHAUSTED'
                FOR UPDATE
                """, (rs, rowNum) -> rs.getString(1), eventId).isEmpty();
    }

    public boolean exists(String eventId) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM paypal_webhook_retry WHERE event_id = ?)", Boolean.class, eventId));
    }

    public int countRequeues(String eventId) {
        Integer prior = jdbc.queryForObject(
                "SELECT COUNT(*) FROM paypal_webhook_retry_operator_audit WHERE event_id = ?",
                Integer.class, eventId);
        return prior == null ? 0 : prior;
    }

    /** 소진 행을 다시 {@code PENDING}으로 올린다. 조건이 맞지 않아 갱신되지 않았으면 {@code false}. */
    public boolean requeueExhausted(String eventId, int newRequeueCount, int maxManualRequeues) {
        int updated = jdbc.update("""
                UPDATE paypal_webhook_retry
                SET state = 'PENDING', attempt_count = 0, next_attempt_at = CURRENT_TIMESTAMP,
                    lease_owner = NULL, lease_until = NULL, last_error = NULL, finished_at = NULL,
                    manual_requeue_count = ?
                WHERE event_id = ? AND state = 'EXHAUSTED' AND manual_requeue_count < ?
                """, newRequeueCount, eventId, maxManualRequeues);
        return updated == 1;
    }

    public void recordRequeue(String idempotencyKey, String eventId, String actor, String reason) {
        jdbc.update("""
                INSERT INTO paypal_webhook_retry_operator_audit
                    (idempotency_key, event_id, actor, reason, outcome)
                VALUES (?, ?, ?, ?, 'REQUEUED')
                """, idempotencyKey, eventId, actor, reason);
    }

    private static ExhaustedRetryRow mapExhausted(ResultSet rs) throws SQLException {
        return new ExhaustedRetryRow(rs.getString("event_id"), rs.getString("event_type"),
                rs.getInt("attempt_count"), instant(rs.getTimestamp("created_at")),
                instant(rs.getTimestamp("finished_at")));
    }

    private static Instant instant(Timestamp value) {
        return value == null ? null : value.toInstant();
    }

    public record ExhaustedRetryRow(
            String eventId, String eventType, int attemptCount, Instant createdAt, Instant finishedAt) { }

    public record RequeueRecord(String eventId, String actor, String reason, String outcome) { }
}
