package com.atlas.application.service;

import com.atlas.application.dto.response.PayPalWebhookPendingRetryPage;
import com.atlas.application.dto.response.PayPalWebhookRetryAttemptHistory;
import com.atlas.application.dto.response.PayPalWebhookRetrySummary;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class PayPalWebhookRetryHistoryService {
    public static final int DEFAULT_PAGE_SIZE = 20;
    public static final int MAX_PAGE_SIZE = 100;
    public static final int MAX_PAGE_OFFSET = 10_000;
    private final JdbcTemplate jdbc;

    public PayPalWebhookRetryHistoryService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public PayPalWebhookRetrySummary summary() {
        Map<String, Long> outcomeCounts = new LinkedHashMap<>();
        jdbc.query("""
                SELECT outcome, COUNT(*) AS outcome_count
                FROM paypal_webhook_retry_attempt_history
                WHERE COALESCE(finished_at, claimed_at) >= CURRENT_TIMESTAMP - INTERVAL '24 hours'
                  AND COALESCE(finished_at, claimed_at) <= CURRENT_TIMESTAMP
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
                """, (rs, row) -> new PayPalWebhookRetrySummary(
                rs.getLong("pending"), rs.getLong("exhausted"),
                instant(rs.getTimestamp("oldest_pending")), instant(rs.getTimestamp("oldest_exhausted")),
                rs.getLong("expired_leases"), outcomeCounts));
    }

    public PayPalWebhookPendingRetryPage pending(int page, int size) {
        long offset = offset(page, size);
        Long total = jdbc.queryForObject(
                "SELECT COUNT(*) FROM paypal_webhook_retry WHERE state = 'PENDING'", Long.class);
        List<PayPalWebhookPendingRetryPage.Item> items = jdbc.query("""
                SELECT event_id, event_type, attempt_count, next_attempt_at,
                       CASE WHEN lease_owner IS NULL THEN 'UNLEASED'
                            WHEN lease_until < CURRENT_TIMESTAMP THEN 'EXPIRED'
                            ELSE 'LEASED' END AS lease_state
                FROM paypal_webhook_retry
                WHERE state = 'PENDING'
                ORDER BY next_attempt_at, created_at, event_id
                LIMIT ? OFFSET ?
                """, (rs, row) -> new PayPalWebhookPendingRetryPage.Item(
                rs.getString("event_id"), rs.getString("event_type"), rs.getInt("attempt_count"),
                instant(rs.getTimestamp("next_attempt_at")), rs.getString("lease_state")), size, offset);
        return new PayPalWebhookPendingRetryPage(total == null ? 0 : total, page, size, items);
    }

    public PayPalWebhookRetryAttemptHistory history(String eventId, int page, int size) {
        long offset = offset(page, size);
        Boolean exists = jdbc.queryForObject("""
                SELECT EXISTS (SELECT 1 FROM paypal_webhook_retry WHERE event_id = ?)
                    OR EXISTS (SELECT 1 FROM paypal_webhook_retry_attempt_history WHERE event_id = ?)
                    OR EXISTS (SELECT 1 FROM paypal_webhook_retry_operator_audit WHERE event_id = ?)
                """, Boolean.class, eventId, eventId, eventId);
        if (!Boolean.TRUE.equals(exists)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Retry event not found");
        }
        Long total = jdbc.queryForObject("""
                SELECT (SELECT COUNT(*) FROM paypal_webhook_retry_attempt_history WHERE event_id = ?)
                     + (SELECT COUNT(*) FROM paypal_webhook_retry_operator_audit
                        WHERE event_id = ? AND outcome = 'REQUEUED')
                """, Long.class, eventId, eventId);
        List<PayPalWebhookRetryAttemptHistory.Entry> entries = jdbc.query("""
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
                """, (rs, row) -> new PayPalWebhookRetryAttemptHistory.Entry(
                rs.getString("category"), instant(rs.getTimestamp("recorded_at")),
                (Integer) rs.getObject("attempt_number")), eventId, eventId, size, offset);
        return new PayPalWebhookRetryAttemptHistory(eventId, total == null ? 0 : total, page, size, entries);
    }

    private static long offset(int page, int size) {
        if (page < 0 || size < 1 || size > MAX_PAGE_SIZE) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "page must be non-negative and size must be between 1 and " + MAX_PAGE_SIZE);
        }
        long offset = (long) page * size;
        if (offset > MAX_PAGE_OFFSET) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "page offset is too large");
        }
        return offset;
    }

    private static Instant instant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }
}
