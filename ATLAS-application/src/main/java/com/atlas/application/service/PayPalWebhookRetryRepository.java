package com.atlas.application.service;

import com.atlas.payment.provider.PaymentWebhookEvent;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** 検証済みPayPalイベントの最小限の再試行情報を永続化する。Issue #277。 */
@Repository
public class PayPalWebhookRetryRepository {
    private static final int MAX_ATTEMPTS = 8;
    private static final int BATCH_SIZE = 1;
    private static final long LEASE_SECONDS = 60;
    private final JdbcTemplate jdbc;

    public PayPalWebhookRetryRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void enqueue(PaymentWebhookEvent event) {
        jdbc.update("""
                INSERT INTO paypal_webhook_retry
                    (event_id, event_type, resource_id, resource_status, provider_order_id,
                     related_capture_id, resource_amount, resource_currency, attempt_count,
                     state, next_attempt_at, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, 0, 'PENDING', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                ON CONFLICT (event_id) DO NOTHING
                """, event.eventId(), event.eventType(), event.resourceId(), event.resourceStatus(),
                event.providerOrderId(), event.relatedCaptureId(), event.resourceAmount(), event.resourceCurrency());
    }

    /** 1件ずつSKIP LOCKEDで取得し、複数インスタンス間の重複claimを防ぐ。 */
        // 마지막 시도를 claim한 worker가 중단되면 lease 만료 후 terminal 행으로 전환한다.
    public List<ClaimedRetry> claimDue() {
        jdbc.update("""
                UPDATE paypal_webhook_retry
                SET state = 'EXHAUSTED',
                    last_error = COALESCE(last_error, 'Worker lease expired on final attempt'),
                    finished_at = COALESCE(finished_at, CURRENT_TIMESTAMP),
                    lease_owner = NULL, lease_until = NULL
                WHERE state = 'PENDING' AND attempt_count >= ?
                  AND (lease_until IS NULL OR lease_until < CURRENT_TIMESTAMP)
                """, MAX_ATTEMPTS);
        String lease = UUID.randomUUID().toString();
        return jdbc.query("""
                WITH due AS (
                    SELECT event_id FROM paypal_webhook_retry
                    WHERE state = 'PENDING' AND attempt_count < ? AND next_attempt_at <= CURRENT_TIMESTAMP
                      AND (lease_until IS NULL OR lease_until < CURRENT_TIMESTAMP)
                    ORDER BY next_attempt_at, created_at
                    LIMIT ? FOR UPDATE SKIP LOCKED
                )
                UPDATE paypal_webhook_retry r
                SET lease_owner = ?, lease_until = CURRENT_TIMESTAMP + (? * INTERVAL '1 second'),
                    attempt_count = r.attempt_count + 1
                FROM due WHERE r.event_id = due.event_id
                RETURNING r.event_id, r.event_type, r.resource_id, r.resource_status,
                          r.provider_order_id, r.related_capture_id, r.resource_amount,
                          r.resource_currency, r.attempt_count, r.lease_owner
                """, (rs, row) -> mapClaim(rs), MAX_ATTEMPTS, BATCH_SIZE, lease, LEASE_SECONDS);
    }

    private ClaimedRetry mapClaim(ResultSet rs) throws SQLException {
        BigDecimal amount = rs.getBigDecimal("resource_amount");
        PaymentWebhookEvent event = new PaymentWebhookEvent(rs.getString("event_id"), rs.getString("event_type"),
                rs.getString("resource_id"), rs.getString("resource_status"), rs.getString("provider_order_id"),
                rs.getString("related_capture_id"), amount, rs.getString("resource_currency"));
        return new ClaimedRetry(event, rs.getInt("attempt_count"), rs.getString("lease_owner"));
    }

    public void complete(String eventId, String leaseOwner) {
        jdbc.update("DELETE FROM paypal_webhook_retry WHERE event_id = ? AND lease_owner = ?", eventId, leaseOwner);
    }

    public void reschedule(String eventId, int attempt, String leaseOwner, String reason) {
        String safeReason = reason == null ? "Retryable event remained unresolved" : reason.substring(0, Math.min(1000, reason.length()));
        if (attempt >= MAX_ATTEMPTS) {
            jdbc.update("""
                    UPDATE paypal_webhook_retry
                    SET state = 'EXHAUSTED', last_error = ?, finished_at = CURRENT_TIMESTAMP,
                        lease_owner = NULL, lease_until = NULL
                    WHERE event_id = ? AND lease_owner = ?
                    """, safeReason, eventId, leaseOwner);
            return;
        }
        long delaySeconds = Math.min(300, 1L << Math.min(attempt - 1, 8));
        jdbc.update("""
                UPDATE paypal_webhook_retry
                SET next_attempt_at = CURRENT_TIMESTAMP + (? * INTERVAL '1 second'),
                    last_error = ?, lease_owner = NULL, lease_until = NULL
                WHERE event_id = ? AND lease_owner = ? AND state = 'PENDING'
                """, delaySeconds, safeReason, eventId, leaseOwner);
    }

    public record ClaimedRetry(PaymentWebhookEvent event, int attempt, String leaseOwner) { }
}
