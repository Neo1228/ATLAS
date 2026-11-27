package com.atlas.settlement.repository;

import com.atlas.settlement.persistence.projection.NegativePayableExposure;
import com.atlas.settlement.persistence.projection.SettlementPayoutRow;
import com.atlas.settlement.model.SettlementPayoutState;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** 지급 실행 저장소. 상태 전이는 조건부 UPDATE 로만 수행한다. */
@Repository
public class SettlementPayoutRepository {
    private final JdbcTemplate jdbc;

    public SettlementPayoutRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 지급을 요청한다. 같은 seller·기간의 진행 중/확정 지급(FAILED 제외)은 partial unique
     * index({@code settlement_payout_seller_period_active_unique})로 하나만 생성된다.
     * FAILED 지급은 이 제한에서 빠지므로 실패 후 같은 기간으로 다시 요청할 수 있다.
     *
     * <p>ON CONFLICT 대상의 WHERE 절은 partial index 추론에 필요하며 인덱스 조건과 같아야 한다.
     *
     * @return 새로 생성됐으면 payoutId, FAILED 가 아닌 같은 기간 지급이 이미 있으면 empty(멱등 재요청)
     */
    public Optional<UUID> request(UUID sellerId, Instant periodStart, Instant periodEnd,
            long amountWon, String idempotencyKey, Instant now) {
        return jdbc.query("""
                INSERT INTO settlement_payout
                    (seller_id, period_start, period_end, amount_won, state, idempotency_key,
                     requested_at, updated_at)
                VALUES (?, ?, ?, ?, 'REQUESTED', ?, ?, ?)
                ON CONFLICT (seller_id, period_start, period_end) WHERE state NOT IN ('FAILED', 'CANCELLED') DO NOTHING
                RETURNING payout_id
                """, resultSet -> resultSet.next()
                ? Optional.of(resultSet.getObject("payout_id", UUID.class)) : Optional.empty(),
                sellerId, Timestamp.from(periodStart), Timestamp.from(periodEnd), amountWon,
                idempotencyKey, Timestamp.from(now), Timestamp.from(now));
    }

    public Optional<SettlementPayoutRow> find(UUID payoutId) {
        return jdbc.query("""
                SELECT payout_id, seller_id, period_start, period_end, amount_won, settled_amount_won, state,
                       requested_at, sent_at, confirmed_at, cancelled_at
                FROM settlement_payout WHERE payout_id = ?
                """, (resultSet, rowNumber) -> map(resultSet), payoutId).stream().findFirst();
    }

    public Optional<SettlementPayoutRow> findByIdempotencyKey(String idempotencyKey) {
        return jdbc.query("""
                SELECT payout_id, seller_id, period_start, period_end, amount_won, settled_amount_won, state,
                       requested_at, sent_at, confirmed_at, cancelled_at
                FROM settlement_payout WHERE idempotency_key = ?
                """, (resultSet, rowNumber) -> map(resultSet), idempotencyKey).stream().findFirst();
    }

    /**
     * 같은 seller·기간의 FAILED 가 아닌 지급을 찾는다. FAILED 지급은 잔액을 예약하지 않으므로
     * 같은 기간의 재요청을 막지 않는다(partial unique index 와 같은 조건).
     */
    public Optional<SettlementPayoutRow> findBySellerAndPeriod(UUID sellerId, Instant periodStart,
            Instant periodEnd) {
        return jdbc.query("""
                SELECT payout_id, seller_id, period_start, period_end, amount_won, settled_amount_won, state,
                       requested_at, sent_at, confirmed_at, cancelled_at
                FROM settlement_payout
                WHERE seller_id = ? AND period_start = ? AND period_end = ? AND state NOT IN ('FAILED', 'CANCELLED')
                """, (resultSet, rowNumber) -> map(resultSet),
                sellerId, Timestamp.from(periodStart), Timestamp.from(periodEnd))
                .stream().findFirst();
    }

    public List<SettlementPayoutRow> findBySeller(UUID sellerId, int limit) {
        return jdbc.query("""
                SELECT payout_id, seller_id, period_start, period_end, amount_won, settled_amount_won, state,
                       requested_at, sent_at, confirmed_at, cancelled_at
                FROM settlement_payout WHERE seller_id = ?
                ORDER BY period_start DESC, payout_id DESC LIMIT ?
                """, (resultSet, rowNumber) -> map(resultSet), sellerId, limit);
    }

    /**
     * 상태를 조건부로 전이한다. 기대 상태가 아니면 0 행이 갱신되어 false 를 돌려준다.
     * 이 조건이 두 replica 의 동시 확정을 막는다.
     */
    public boolean transition(UUID payoutId, SettlementPayoutState expected,
            SettlementPayoutState next, Instant now) {
        int updated = jdbc.update("""
                UPDATE settlement_payout
                SET state = ?, updated_at = ?,
                    sent_at = CASE WHEN ? = 'SENT' THEN ? ELSE sent_at END,
                    confirmed_at = CASE WHEN ? = 'CONFIRMED' THEN ? ELSE confirmed_at END,
                    cancelled_at = CASE WHEN ? = 'CANCELLED' THEN ? ELSE cancelled_at END,
                    settled_amount_won = CASE WHEN ? = 'CONFIRMED' THEN amount_won ELSE settled_amount_won END
                WHERE payout_id = ? AND state = ?
                """, next.name(), Timestamp.from(now), next.name(), Timestamp.from(now),
                next.name(), Timestamp.from(now), next.name(), Timestamp.from(now), next.name(),
                payoutId, expected.name());
        return updated == 1;
    }

    public boolean fail(UUID payoutId, String failureCode, Instant now) {
        int updated = jdbc.update("""
                UPDATE settlement_payout
                SET state = 'FAILED', failure_code = ?, updated_at = ?
                WHERE payout_id = ? AND state IN ('REQUESTED', 'APPROVED')
                """, failureCode, Timestamp.from(now), payoutId);
        return updated == 1;
    }

    public boolean confirmWithProviderEvidence(UUID payoutId, long providerDebitBookValueWon,
            long fxRoundingExpenseWon, long providerFeeWon, Instant now) {
        int updated = jdbc.update("""
                UPDATE settlement_payout
                SET state = 'CONFIRMED', settled_amount_won = amount_won,
                    provider_debit_book_value_won = ?, payout_fx_rounding_expense_won = ?,
                    provider_fee_won = ?, sent_at = COALESCE(sent_at, ?),
                    confirmed_at = ?, updated_at = ?
                WHERE payout_id = ? AND state IN (
                    'DISPATCHING', 'SENT', 'UNKNOWN', 'UNCLAIMED', 'NEEDS_REVIEW')
                """, providerDebitBookValueWon, fxRoundingExpenseWon, providerFeeWon,
                Timestamp.from(now), Timestamp.from(now), Timestamp.from(now), payoutId);
        return updated == 1;
    }

    public boolean providerConfirmationMatches(UUID payoutId, long providerDebitBookValueWon,
            long fxRoundingExpenseWon, long providerFeeWon) {
        Boolean matches = jdbc.queryForObject("""
                SELECT EXISTS (
                    SELECT 1 FROM settlement_payout
                    WHERE payout_id = ? AND state = 'CONFIRMED'
                        AND provider_debit_book_value_won = ?
                        AND payout_fx_rounding_expense_won = ?
                        AND provider_fee_won = ?)
                """, Boolean.class, payoutId, providerDebitBookValueWon, fxRoundingExpenseWon, providerFeeWon);
        return Boolean.TRUE.equals(matches);
    }

    public boolean failWithProviderEvidence(UUID payoutId, String failureCode, Instant now) {
        int updated = jdbc.update("""
                UPDATE settlement_payout
                SET state = 'FAILED', failure_code = ?, updated_at = ?
                WHERE payout_id = ? AND state IN (
                    'DISPATCHING', 'SENT', 'UNKNOWN', 'UNCLAIMED', 'NEEDS_REVIEW')
                """, failureCode, Timestamp.from(now), payoutId);
        return updated == 1;
    }

    public boolean markNeedsReview(UUID payoutId, String reasonCode, Instant now) {
        int updated = jdbc.update("""
                UPDATE settlement_payout
                SET state = 'NEEDS_REVIEW', failure_code = ?, updated_at = ?
                WHERE payout_id = ? AND state IN ('DISPATCHING', 'SENT', 'UNKNOWN', 'UNCLAIMED')
                """, reasonCode, Timestamp.from(now), payoutId);
        return updated == 1;
    }

    /** Cancel payout reservations that have not crossed the provider dispatch fence. */
    public int cancelUnsentForRefund(UUID sellerId, Instant now) {
        return jdbc.update("""
                UPDATE settlement_payout
                SET state = 'CANCELLED', failure_code = 'REFUND_PREEMPTED',
                    cancelled_at = ?, updated_at = ?
                WHERE seller_id = ? AND state IN ('REQUESTED', 'APPROVED')
                """, Timestamp.from(now), Timestamp.from(now), sellerId);
    }

    public boolean cancelBeforeDispatch(UUID payoutId, String reasonCode, Instant now) {
        return jdbc.update("""
                UPDATE settlement_payout
                SET state = 'CANCELLED', failure_code = ?, cancelled_at = ?, updated_at = ?
                WHERE payout_id = ? AND state IN ('REQUESTED', 'APPROVED')
                """, reasonCode, Timestamp.from(now), Timestamp.from(now), payoutId) == 1;
    }

    /** 확정되지 않은(REQUESTED/APPROVED/SENT) 지급의 합계. 아직 보내지 않은 돈이다. */
    public long pendingAmount(UUID sellerId) {
        Long total = jdbc.queryForObject("""
                SELECT COALESCE(SUM(amount_won), 0) FROM settlement_payout
                WHERE seller_id = ? AND state IN (
                    'REQUESTED', 'APPROVED', 'DISPATCHING', 'SENT', 'UNKNOWN', 'UNCLAIMED', 'NEEDS_REVIEW')
                """, Long.class, sellerId);
        return total == null ? 0 : total;
    }

    /** 확정된 지급의 합계. 이미 지급한 돈이다. */
    public long confirmedAmount(UUID sellerId) {
        Long total = jdbc.queryForObject("""
                SELECT COALESCE(SUM(settled_amount_won), 0) FROM settlement_payout
                WHERE seller_id = ? AND state = 'CONFIRMED'
                """, Long.class, sellerId);
        return total == null ? 0 : total;
    }

    /**
     * Global negative Seller payable exposure after confirmed transfers and refunds.
     *
     * <p>판매자 잔액 마감 행 + 마감 커서 이후 항목(꼬리)으로 센다(ADR-0104). 30초마다 도는 지표라 원장 이력 전체를
     * 판매자별로 모으지 않는다. 커서는 리터럴로 넣어(일반 계획이 꼬리 범위를 모르는 것을 피함) 두 번째 문장이 같은
     * 커서를 다시 확인한다. 그 사이 마감이 커서를 옮겼으면(마감 행이 꼬리 일부를 이미 더함) 다시 읽는다.
     */
    public NegativePayableExposure negativePayableExposure() {
        for (int attempt = 0; attempt < 3; attempt++) {
            UUID cursor = jdbc.queryForObject("SELECT entry_id FROM settlement_balance_checkpoint_cursor", UUID.class);
            List<NegativePayableExposure> exposure = jdbc.query("""
                    WITH tail AS (
                        SELECT seller_id,
                            SUM(CASE WHEN entry_side = 'CREDIT' THEN amount_won ELSE -amount_won END) AS payable
                        FROM settlement_entry
                        WHERE account_code = 'SELLER_PAYABLE' AND seller_id IS NOT NULL AND entry_id >= '%1$s'::uuid
                        GROUP BY seller_id
                    ), seller_payables AS (
                        SELECT COALESCE(b.payable_won, 0) + COALESCE(t.payable, 0) AS payable
                        FROM settlement_seller_balance_checkpoint b
                        FULL JOIN tail t USING (seller_id)
                    )
                    SELECT COUNT(*) FILTER (WHERE payable < 0) AS seller_count,
                        COALESCE(SUM(-payable) FILTER (WHERE payable < 0), 0) AS exposure_amount_won
                    FROM seller_payables
                    HAVING (SELECT entry_id FROM settlement_balance_checkpoint_cursor) = '%1$s'::uuid
                    """.formatted(cursor), (row, number) -> new NegativePayableExposure(
                    row.getLong("seller_count"), row.getLong("exposure_amount_won")));
            if (!exposure.isEmpty()) {
                return exposure.get(0);
            }
        }
        throw new IllegalStateException("Balance checkpoint cursor kept moving while reading payable exposure");
    }

    private static SettlementPayoutRow map(ResultSet resultSet) throws SQLException {
        Timestamp sentAt = resultSet.getTimestamp("sent_at");
        Timestamp confirmedAt = resultSet.getTimestamp("confirmed_at");
        Timestamp cancelledAt = resultSet.getTimestamp("cancelled_at");
        return new SettlementPayoutRow(
                resultSet.getObject("payout_id", UUID.class),
                resultSet.getObject("seller_id", UUID.class),
                resultSet.getTimestamp("period_start").toInstant(),
                resultSet.getTimestamp("period_end").toInstant(),
                resultSet.getLong("amount_won"),
                resultSet.getLong("settled_amount_won"),
                SettlementPayoutState.valueOf(resultSet.getString("state")),
                resultSet.getTimestamp("requested_at").toInstant(),
                sentAt == null ? null : sentAt.toInstant(),
                confirmedAt == null ? null : confirmedAt.toInstant(),
                cancelledAt == null ? null : cancelledAt.toInstant());
    }
}
