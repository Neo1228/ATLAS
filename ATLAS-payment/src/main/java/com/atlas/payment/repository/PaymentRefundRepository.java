package com.atlas.payment.repository;

import com.atlas.payment.persistence.entity.PaymentRefundOperation;
import com.atlas.payment.persistence.state.PaymentRefundState;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

@Repository
public class PaymentRefundRepository {
    private static final RowMapper<PaymentRefundOperation> ROW_MAPPER = (resultSet, rowNumber) ->
            new PaymentRefundOperation(
                    resultSet.getObject("refund_operation_id", UUID.class),
                    resultSet.getObject("attempt_id", UUID.class),
                    resultSet.getString("capture_id"),
                    resultSet.getString("idempotency_key"),
                    resultSet.getLong("amount_won"),
                    resultSet.getString("currency"),
                    resultSet.getObject("provider_request_id", UUID.class),
                    resultSet.getString("provider_refund_id"),
                    resultSet.getString("provider_status"),
                    PaymentRefundState.valueOf(resultSet.getString("refund_state")),
                    resultSet.getTimestamp("created_at").toInstant(),
                    resultSet.getTimestamp("updated_at").toInstant());

    private final JdbcTemplate jdbc;

    public PaymentRefundRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void lockAttempt(UUID attemptId) {
        jdbc.query("SELECT pg_advisory_xact_lock(hashtextextended(?, 0))",
                (RowCallbackHandler) resultSet -> { }, attemptId.toString());
    }
    public PaymentRefundOperation reserve(UUID operationId, UUID attemptId, String captureId,
            String idempotencyKey, long amountWon, UUID providerRequestId, Instant now) {
        jdbc.update("""
                INSERT INTO payment_refund_operation
                    (refund_operation_id, attempt_id, capture_id, idempotency_key, amount_won, currency,
                     provider_request_id, refund_state, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, 'KRW', ?, 'PENDING', ?, ?)
                ON CONFLICT (attempt_id, idempotency_key) DO NOTHING
                """, operationId, attemptId, captureId, idempotencyKey, amountWon,
                providerRequestId, Timestamp.from(now), Timestamp.from(now));
        return findByAttemptIdAndIdempotencyKeyForUpdate(attemptId, idempotencyKey)
                .orElseThrow(() -> new IllegalStateException("Refund operation reservation was not persisted"));
    }

    public Optional<PaymentRefundOperation> findByAttemptIdAndIdempotencyKeyForUpdate(
            UUID attemptId, String idempotencyKey) {
        return queryOne("""
                SELECT * FROM payment_refund_operation
                WHERE attempt_id = ? AND idempotency_key = ?
                FOR UPDATE
                """, attemptId, idempotencyKey);
    }

    public Optional<PaymentRefundOperation> findByProviderRefundIdForUpdate(String providerRefundId) {
        return queryOne("""
                SELECT * FROM payment_refund_operation
                WHERE provider_refund_id = ?
                FOR UPDATE
                """, providerRefundId);
    }

    public Optional<PaymentRefundOperation> findByOperationIdForUpdate(UUID refundOperationId) {
        return queryOne("""
                SELECT * FROM payment_refund_operation
                WHERE refund_operation_id = ?
                FOR UPDATE
                """, refundOperationId);
    }

    public void updateResult(UUID refundOperationId, PaymentRefundState state, String providerRefundId,
            String providerStatus, Instant updatedAt) {
        int updated = jdbc.update("""
                UPDATE payment_refund_operation
                SET refund_state = CASE WHEN refund_state = 'COMPLETED' THEN refund_state ELSE ? END,
                    provider_refund_id = COALESCE(?, provider_refund_id),
                    provider_status = COALESCE(?, provider_status),
                    updated_at = ?
                WHERE refund_operation_id = ?
                """, state.name(), providerRefundId, providerStatus, Timestamp.from(updatedAt), refundOperationId);
        if (updated != 1) throw new IllegalStateException("Refund operation disappeared during update");
    }

    public List<PaymentRefundOperation> findPendingForAttempt(UUID attemptId) {
        return jdbc.query("""
                SELECT * FROM payment_refund_operation
                WHERE attempt_id = ? AND refund_state IN ('PENDING', 'UNKNOWN')
                ORDER BY created_at, refund_operation_id
                """, ROW_MAPPER, attemptId);
    }

    public long refundedAmountForAttempt(UUID attemptId) {
        Long amount = jdbc.queryForObject("""
                SELECT COALESCE(SUM(amount_won), 0)
                FROM payment_refund_operation
                WHERE attempt_id = ? AND refund_state <> 'FAILED'
                """, Long.class, attemptId);
        return amount == null ? 0L : amount;
    }
    private Optional<PaymentRefundOperation> queryOne(String sql, Object... parameters) {
        return jdbc.query(sql, ROW_MAPPER, parameters).stream().findFirst();
    }
}
