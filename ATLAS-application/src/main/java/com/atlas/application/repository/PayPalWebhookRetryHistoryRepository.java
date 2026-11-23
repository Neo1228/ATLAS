package com.atlas.application.repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** PayPal webhook 재시도 큐와 시도 이력의 운영자용 읽기 모델. */
@Repository
public class PayPalWebhookRetryHistoryRepository {
    private final JdbcTemplate jdbc;

    public PayPalWebhookRetryHistoryRepository(JdbcTemplate jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc);
    }

    /**
     * 최근 24시간 재시도 outcome 요약.
     *
     * <p>{@code recorded_at} 은 {@code COALESCE(finished_at, claimed_at)} 인 생성 컬럼이다.
     * 원래 술어가 COALESCE 를 직접 쓰면 인덱스를 못 타 이력 테이블 전체를 스캔했다
     * (200,000행에서 2,705 버퍼). 생성 컬럼 + (recorded_at, outcome) 인덱스로
     * Index Only Scan 31 버퍼가 됐다(V54, EXP-047).
     */
    public RetrySummary summary() {
        Map<String, Long> outcomeCounts = new LinkedHashMap<>();
        jdbc.query("""
                SELECT outcome, COUNT(*) AS outcome_count
                FROM paypal_webhook_retry_attempt_history
                WHERE recorded_at >= CURRENT_TIMESTAMP - INTERVAL '24 hours'
                  AND recorded_at <= CURRENT_TIMESTAMP
                GROUP BY outcome
                ORDER BY outcome
                """, rs -> {
                    outcomeCounts.put(rs.getString("outcome"), rs.getLong("outcome_count"));
                });
        return jdbc.queryForObject("""
                SELECT COUNT(*) FILTER (WHERE state = 'PENDING') AS pending,
                       COUNT(*) FILTER (WHERE state = 'EXHAUSTED') AS exhausted,
                       MIN(created_at) FILTER (WHERE state = 'PENDING') AS oldest_pending,
                       MIN(finished_at) FILTER (WHERE state = 'EXHAUSTED') AS oldest_exhausted,
                       COUNT(*) FILTER (WHERE lease_owner IS NOT NULL
                                         AND lease_until < CURRENT_TIMESTAMP) AS expired_leases
                FROM paypal_webhook_retry
                """, (rs, row) -> new RetrySummary(
                rs.getLong("pending"), rs.getLong("exhausted"),
                instant(rs.getTimestamp("oldest_pending")), instant(rs.getTimestamp("oldest_exhausted")),
                rs.getLong("expired_leases"), outcomeCounts));
    }

    public long countPending() {
        Long total = jdbc.queryForObject(
                "SELECT COUNT(*) FROM paypal_webhook_retry WHERE state = 'PENDING'", Long.class);
        return total == null ? 0 : total;
    }

    public List<PendingRetry> listPending(int size, long offset) {
        return jdbc.query("""
                SELECT event_id, event_type, attempt_count, next_attempt_at,
                       CASE WHEN lease_owner IS NULL THEN 'UNLEASED'
                            WHEN lease_until < CURRENT_TIMESTAMP THEN 'EXPIRED'
                            ELSE 'LEASED' END AS lease_state
                FROM paypal_webhook_retry
                WHERE state = 'PENDING'
                ORDER BY next_attempt_at, created_at, event_id
                LIMIT ? OFFSET ?
                """, (rs, row) -> new PendingRetry(
                rs.getString("event_id"), rs.getString("event_type"), rs.getInt("attempt_count"),
                instant(rs.getTimestamp("next_attempt_at")), rs.getString("lease_state")), size, offset);
    }

    /** 큐, 시도 이력, 운영자 감사 중 어디에든 기록이 있는 이벤트인지. 큐 행이 정리된 뒤에도 이력은 조회할 수 있다. */
    public boolean eventKnown(String eventId) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS (SELECT 1 FROM paypal_webhook_retry WHERE event_id = ?)
                    OR EXISTS (SELECT 1 FROM paypal_webhook_retry_attempt_history WHERE event_id = ?)
                    OR EXISTS (SELECT 1 FROM paypal_webhook_retry_operator_audit WHERE event_id = ?)
                """, Boolean.class, eventId, eventId, eventId));
    }

    public long countTimeline(String eventId) {
        Long total = jdbc.queryForObject("""
                SELECT (SELECT COUNT(*) FROM paypal_webhook_retry_attempt_history WHERE event_id = ?)
                     + (SELECT COUNT(*) FROM paypal_webhook_retry_operator_audit
                        WHERE event_id = ? AND outcome = 'REQUEUED')
                """, Long.class, eventId, eventId);
        return total == null ? 0 : total;
    }

    public List<TimelineEntry> timeline(String eventId, int size, long offset) {
        return jdbc.query("""
                SELECT category, recorded_at, attempt_number FROM (
                    SELECT outcome AS category, COALESCE(finished_at, claimed_at) AS recorded_at,
                           attempt_number, CAST(id AS VARCHAR) AS sort_key
                    FROM paypal_webhook_retry_attempt_history WHERE event_id = ?
                    UNION ALL
                    SELECT 'MANUAL_REQUEUE' AS category, created_at AS recorded_at,
                           CAST(NULL AS INTEGER) AS attempt_number, idempotency_key AS sort_key
                    FROM paypal_webhook_retry_operator_audit WHERE event_id = ? AND outcome = 'REQUEUED'
                ) timeline
                ORDER BY recorded_at DESC, category, attempt_number DESC NULLS LAST, sort_key
                LIMIT ? OFFSET ?
                """, (rs, row) -> new TimelineEntry(
                rs.getString("category"), instant(rs.getTimestamp("recorded_at")),
                (Integer) rs.getObject("attempt_number")), eventId, eventId, size, offset);
    }

    private static Instant instant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }

    public record RetrySummary(long pending, long exhausted, Instant oldestPendingSince,
            Instant oldestExhaustedSince, long expiredLeaseCount, Map<String, Long> last24HoursOutcomeCounts) {
    }

    public record PendingRetry(String eventId, String eventType, int attemptCount, Instant nextAttemptAt,
            String leaseState) {
    }

    public record TimelineEntry(String category, Instant recordedAt, Integer attemptNumber) {
    }
}
