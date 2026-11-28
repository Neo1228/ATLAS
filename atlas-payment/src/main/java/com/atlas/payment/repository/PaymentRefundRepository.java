package com.atlas.payment.repository;

import com.atlas.payment.persistence.entity.PaymentRefundOperation;
import com.atlas.payment.model.PaymentRefundState;
import com.atlas.payment.persistence.projection.RefundedTotals;
import java.sql.Timestamp;
import java.time.Instant;
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
                    resultSet.getObject("provider_amount_minor", Long.class),
                    resultSet.getString("provider_currency"),
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
            String idempotencyKey, long amountWon, long providerAmountMinor, String providerCurrency,
            UUID providerRequestId, Instant now) {
        jdbc.update("""
                INSERT INTO payment_refund_operation
                    (refund_operation_id, attempt_id, capture_id, idempotency_key, amount_won, currency,
                     provider_amount_minor, provider_currency, provider_request_id, refund_state,
                     created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, 'KRW', ?, ?, ?, 'PENDING', ?, ?)
                ON CONFLICT (attempt_id, idempotency_key) DO NOTHING
                """, operationId, attemptId, captureId, idempotencyKey, amountWon,
                providerAmountMinor, providerCurrency, providerRequestId, Timestamp.from(now), Timestamp.from(now));
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
        // UPDATE 는 FOR NO KEY UPDATE 만 잡는다. 같은 트랜잭션이 뒤이어 FOR UPDATE 로 올리는 사이,
        // 다른 트랜잭션이 이 행을 가리키는 FK 갱신으로 FOR KEY SHARE 를 쥔 채 FOR UPDATE 로 올리면
        // 서로의 약한 락을 기다리는 교착(40P01)이 된다. 처음부터 FOR UPDATE 로 잡아 올림 자체를 없앤다.
        jdbc.query("SELECT 1 FROM payment_refund_operation WHERE refund_operation_id = ? FOR UPDATE",
                (RowCallbackHandler) resultSet -> { }, refundOperationId);
        int updated = jdbc.update("""
                UPDATE payment_refund_operation
                SET refund_state = CASE WHEN refund_state IN ('COMPLETED', 'FAILED') THEN refund_state ELSE ? END,
                    provider_refund_id = COALESCE(?, provider_refund_id),
                    provider_status = COALESCE(?, provider_status),
                    updated_at = ?
                WHERE refund_operation_id = ?
                """, state.name(), providerRefundId, providerStatus, Timestamp.from(updatedAt), refundOperationId);
        if (updated != 1) throw new IllegalStateException("Refund operation disappeared during update");
    }

    /**
     * capture 남은 금액을 정하는 유일한 합. 우리 환불({@code PaymentRefundWorkflow})과 provider 발 환불·역전·판매자 패소 분쟁(ADR-0115)
     * ({@code ProviderAdjustmentRecorder})이 둘 다 이 값을 빼므로 한쪽이 다른 쪽을 모르고 capture를 넘겨 환불하지 않는다.
     * 두 표의 원화·센트는 방향이 다르게 환산된다(ADR-0113 "환불 합"): 우리 환불은 원화가 기준이고 센트는 누적 원화를
     * HALF_UP 한 값, provider 사건은 센트가 기준이고 원화는 capture 비율로 내린 값이다. 어느 쪽이든 각 행의 원화·센트는
     * 같은 사건의 두 표현이라 그대로 더한다.
     */
    public RefundedTotals refundedTotalsForAttempt(UUID attemptId) {
        return jdbc.queryForObject("""
                SELECT COALESCE(SUM(provider_amount_minor), 0) AS provider_amount_minor,
                       COALESCE(SUM(amount_won), 0) AS amount_won
                FROM (
                    SELECT provider_amount_minor, amount_won FROM payment_refund_operation
                    WHERE attempt_id = ? AND refund_state <> 'FAILED'
                    UNION ALL
                    SELECT provider_amount_minor, amount_won FROM payment_provider_adjustment
                    WHERE attempt_id = ? AND kind IN ('REFUND', 'REVERSAL', 'DISPUTE')
                      AND status IN ('POSTING', 'POSTED')
                ) refunded
                """, (resultSet, rowNumber) -> new RefundedTotals(
                        resultSet.getLong("provider_amount_minor"), resultSet.getLong("amount_won")),
                attemptId, attemptId);
    }

    public boolean hasUnpricedNonFailedRefunds(UUID attemptId) {
        Boolean exists = jdbc.queryForObject("""
                SELECT EXISTS (
                    SELECT 1 FROM payment_refund_operation
                    WHERE attempt_id = ? AND refund_state <> 'FAILED' AND provider_amount_minor IS NULL
                )
                """, Boolean.class, attemptId);
        return Boolean.TRUE.equals(exists);
    }

    /**
     * PayPal 에 보냈을 수 있지만 refund id 를 아직 저장하지 못한 우리 환불이 있는가. operation 은 PayPal 호출 전에
     * commit 되므로, 그 호출이 만든 refund 의 웹훅이 도착할 때는 이 행이 반드시 보인다.
     */
    public boolean hasProviderRefundInFlight(UUID attemptId) {
        Boolean exists = jdbc.queryForObject("""
                SELECT EXISTS (
                    SELECT 1 FROM payment_refund_operation
                    WHERE attempt_id = ? AND refund_state <> 'FAILED' AND provider_refund_id IS NULL
                )
                """, Boolean.class, attemptId);
        return Boolean.TRUE.equals(exists);
    }
    private Optional<PaymentRefundOperation> queryOne(String sql, Object... parameters) {
        return jdbc.query(sql, ROW_MAPPER, parameters).stream().findFirst();
    }
}