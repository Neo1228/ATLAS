package com.atlas.application.repository.payout;

import com.atlas.application.service.payout.PayoutDispatchPlan;
import com.atlas.application.service.payout.PayoutDispatchRecord;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class PayoutDispatchRepository {
    private final JdbcTemplate jdbc;

    public PayoutDispatchRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @Transactional(readOnly = true)
    public Optional<PayoutDispatchRecord> find(UUID payoutId) {
        return jdbc.query("SELECT * FROM settlement_payout_dispatch_outbox WHERE payout_id = ?",
                (rs, row) -> map(rs), payoutId).stream().findFirst();
    }

    /** Idempotently enqueue approval-time facts; conflicting financial facts are never overwritten. */
    @Transactional
    public PayoutDispatchRecord enqueue(PayoutDispatchPlan p) {
        Objects.requireNonNull(p);
        jdbc.update("""
                INSERT INTO settlement_payout_dispatch_outbox (
                  payout_id, profile_version, recipient_email_ciphertext, recipient_country_code,
                  payout_currency, requested_amount_won, provider_amount_minor, fx_rate_won_per_usd,
                  fx_source, fx_as_of_date, provider_debit_book_value_won, payout_fx_rounding_expense_won,
                  sender_batch_id, sender_item_id, paypal_request_id, request_payload_sha256,
                  status, available_at, first_enqueued_at, created_at, updated_at)
                VALUES (?, ?, ?, ?, 'USD', ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'PENDING', now(), now(), now(), now())
                ON CONFLICT (payout_id) DO NOTHING
                """, p.payoutId(), p.profileVersion(), p.recipientEmailCiphertext(), p.recipientCountryCode(),
                p.requestedAmountWon(), p.providerAmountMinor(), p.fxRateWonPerUsd(), p.fxSource(), p.fxAsOfDate(),
                p.providerDebitBookValueWon(), p.payoutFxRoundingExpenseWon(), p.senderBatchId(), p.senderItemId(),
                p.paypalRequestId(), p.requestPayloadSha256());
        return find(p.payoutId()).orElseThrow(
                () -> new IllegalStateException("Payout dispatch disappeared after enqueue"));
    }

    /** Lease due work atomically across instances. Expired leases are reclaimed with a fresh token. */
    @Transactional
    public List<PayoutDispatchRecord> claim(int limit, Duration leaseDuration) {
        if (limit <= 0 || leaseDuration.isNegative() || leaseDuration.isZero()) return List.of();
        return jdbc.query("""
                WITH candidates AS (
                  SELECT o.payout_id FROM settlement_payout_dispatch_outbox o
                  WHERE ((o.status IN ('PENDING','WAITING_PROVIDER') AND o.available_at <= now())
                     OR (o.status='LEASED' AND o.lease_until <= now()))
                  ORDER BY o.available_at, o.first_enqueued_at, o.payout_id
                  LIMIT ?
                  FOR UPDATE OF o SKIP LOCKED
                )
                UPDATE settlement_payout_dispatch_outbox o
                SET status='LEASED', lease_token=gen_random_uuid(), lease_until=now() + (? * interval '1 millisecond'),
                    attempt_count=o.attempt_count+1, last_attempt_at=now(), updated_at=now()
                FROM candidates c WHERE o.payout_id=c.payout_id
                RETURNING o.*
                """, (rs, row) -> map(rs), limit, leaseDuration.toMillis());
    }


    @Transactional
    public boolean reschedule(PayoutDispatchRecord r, Duration delay, String safeCode, String resultingStatus) {
        if (!List.of("PENDING", "WAITING_PROVIDER", "NEEDS_REVIEW").contains(resultingStatus))
            throw new IllegalArgumentException("Invalid dispatch retry status");
        return jdbc.update("""
                UPDATE settlement_payout_dispatch_outbox SET status=?, available_at=now() + (? * interval '1 millisecond'),
                  last_error_code=?, lease_token=NULL, lease_until=NULL, updated_at=now()
                WHERE payout_id=? AND status='LEASED' AND lease_token=?
                """, resultingStatus, Math.max(0, delay.toMillis()), safeCode, r.payoutId(), r.leaseToken()) == 1;
    }

    @Transactional
    public boolean transition(PayoutDispatchRecord r, String status, String safeCode) {
        if (!List.of("COMPLETED", "FAILED", "CANCELLED", "NEEDS_REVIEW", "WAITING_PROVIDER", "PENDING").contains(status))
            throw new IllegalArgumentException("Invalid dispatch status");
        return jdbc.update("""
                UPDATE settlement_payout_dispatch_outbox SET status=?, last_error_code=?, lease_token=NULL,
                  lease_until=NULL, updated_at=now() WHERE payout_id=? AND status='LEASED' AND lease_token=?
                """, status, safeCode, r.payoutId(), r.leaseToken()) == 1;
    }

    @Transactional
    public boolean cancelPending(UUID payoutId) {
        return jdbc.update("""
                UPDATE settlement_payout_dispatch_outbox
                SET status = 'CANCELLED', last_error_code = 'OPERATOR_CANCELLED', updated_at = now()
                WHERE payout_id = ? AND status = 'PENDING'
                """, payoutId) == 1;
    }

    @Transactional
    public boolean requeueNeedsReview(UUID payoutId) {
        return jdbc.update("""
                UPDATE settlement_payout_dispatch_outbox
                SET status = CASE WHEN provider_batch_id IS NULL THEN 'PENDING' ELSE 'WAITING_PROVIDER' END,
                    attempt_count = 0, available_at = now(), last_error_code = 'OPERATOR_REQUEUED',
                    updated_at = now()
                WHERE payout_id = ? AND status = 'NEEDS_REVIEW'
                """, payoutId) == 1;
    }

    @Transactional
    public boolean resolveNeedsReview(UUID payoutId, String result, String batchId, String itemId,
            Long feeMinor, Long feeWon, String safeCode) {
        if (!List.of("COMPLETED", "FAILED").contains(result)) {
            throw new IllegalArgumentException("Invalid manual payout resolution");
        }
        String itemStatus = "COMPLETED".equals(result) ? "SUCCESS" : "FAILED";
        String feeCurrency = feeMinor == null ? null : "USD";
        return jdbc.update("""
                UPDATE settlement_payout_dispatch_outbox
                SET status = ?, provider_batch_id = COALESCE(?, provider_batch_id),
                    provider_batch_status = 'MANUAL_RECONCILIATION',
                    provider_item_id = COALESCE(?, provider_item_id),
                    provider_item_status = ?, provider_fee_minor = ?, provider_fee_currency = ?,
                    provider_fee_won = ?, last_error_code = ?, updated_at = now()
                WHERE payout_id = ? AND status = 'NEEDS_REVIEW'
                """, result, batchId, itemId, itemStatus, feeMinor, feeCurrency, feeWon,
                safeCode, payoutId) == 1;
    }


    @Transactional
    public boolean recordProviderEvidence(PayoutDispatchRecord r, String batchId, String batchStatus,
                                          String itemId, String itemStatus, Long feeMinor,
                                          String feeCurrency, Long feeWon) {
        return jdbc.update("""
                UPDATE settlement_payout_dispatch_outbox SET provider_batch_id=COALESCE(?, provider_batch_id),
                  provider_batch_status=?, provider_item_id=?, provider_item_status=?, provider_fee_minor=?,
                  provider_fee_currency=?, provider_fee_won=?, updated_at=now()
                WHERE payout_id=? AND status='LEASED' AND lease_token=?
                """, batchId, batchStatus, itemId, itemStatus, feeMinor, feeCurrency, feeWon,
                r.payoutId(), r.leaseToken()) == 1;
    }

    public MetricsSnapshot metricsSnapshot() {
        return jdbc.queryForObject("""
                SELECT count(*) FILTER (WHERE status IN ('PENDING','WAITING_PROVIDER')) backlog,
                  count(*) FILTER (WHERE status='LEASED') leased,
                  count(*) FILTER (WHERE status='NEEDS_REVIEW') needs_review,
                  count(*) FILTER (WHERE status='COMPLETED') completed,
                  count(*) FILTER (WHERE status='FAILED') failed,
                  COALESCE(EXTRACT(EPOCH FROM (
                    now() - MIN(first_enqueued_at) FILTER (
                      WHERE status IN ('PENDING','WAITING_PROVIDER','LEASED')))), 0) oldest_age_seconds
                FROM settlement_payout_dispatch_outbox
                """, (rs, n) -> new MetricsSnapshot(rs.getLong("backlog"), rs.getLong("leased"),
                rs.getLong("needs_review"), rs.getLong("completed"), rs.getLong("failed"),
                rs.getDouble("oldest_age_seconds")));
    }

    public List<PayoutDispatchRecord> findNeedsReview(int limit, int offset) {
        return jdbc.query("""
                SELECT * FROM settlement_payout_dispatch_outbox
                WHERE status = 'NEEDS_REVIEW'
                ORDER BY first_enqueued_at, payout_id
                LIMIT ? OFFSET ?
                """, (rs, row) -> map(rs), limit, offset);
    }

    private static PayoutDispatchRecord map(ResultSet rs) throws SQLException {
        return new PayoutDispatchRecord(rs.getObject("payout_id", UUID.class), rs.getInt("profile_version"),
                rs.getString("recipient_email_ciphertext"), rs.getString("recipient_country_code"),
                rs.getString("payout_currency"), rs.getLong("requested_amount_won"), rs.getLong("provider_amount_minor"),
                rs.getBigDecimal("fx_rate_won_per_usd"), rs.getString("fx_source"), rs.getDate("fx_as_of_date").toLocalDate(),
                rs.getLong("provider_debit_book_value_won"), rs.getLong("payout_fx_rounding_expense_won"),
                (Long) rs.getObject("provider_fee_minor"), rs.getString("provider_fee_currency"),
                (Long) rs.getObject("provider_fee_won"), rs.getString("sender_batch_id"), rs.getString("sender_item_id"),
                rs.getString("paypal_request_id"), rs.getString("request_payload_sha256"), rs.getString("provider_batch_id"),
                rs.getString("provider_item_id"), rs.getString("provider_batch_status"), rs.getString("provider_item_status"),
                rs.getString("status"), rs.getTimestamp("available_at").toInstant(),
                rs.getTimestamp("first_enqueued_at").toInstant(), rs.getInt("attempt_count"),
                rs.getTimestamp("last_attempt_at") == null ? null : rs.getTimestamp("last_attempt_at").toInstant(),
                rs.getString("last_error_code"), rs.getObject("lease_token", UUID.class),
                rs.getTimestamp("lease_until") == null ? null : rs.getTimestamp("lease_until").toInstant());
    }

    public record MetricsSnapshot(long backlog, long leased, long needsReview, long completed,
            long failed, double oldestAgeSeconds) { }
}
