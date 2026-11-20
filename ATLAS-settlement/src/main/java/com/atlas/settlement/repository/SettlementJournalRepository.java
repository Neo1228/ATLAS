package com.atlas.settlement.repository;

import com.atlas.settlement.persistence.projection.CaptureJournalRecord;
import com.atlas.settlement.persistence.projection.CheckoutSettlementAllocation;
import com.atlas.settlement.persistence.projection.CheckoutSettlementRecord;
import com.atlas.settlement.persistence.projection.RefundJournalRecord;
import com.atlas.settlement.persistence.projection.SellerBalance;
import com.atlas.settlement.persistence.projection.SellerStatementRow;
import com.atlas.settlement.persistence.projection.SellerStatementTotals;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.stereotype.Repository;

@Repository
public class SettlementJournalRepository {
    private final JdbcTemplate jdbc;

    public SettlementJournalRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

 public Optional<UUID> insertIfAbsent(CaptureJournalRecord request, Instant recordedAt) {
        return insertIfAbsent(request, 0, 0, 0, 0, recordedAt);
    }

    /**
     * capture 분개를 기록한다. 수수료·보류와 적용 요율을 같은 행에 스냅샷해, 정책이 바뀌어도
     * 과거 분개가 재계산되지 않게 한다.
     */
    public Optional<UUID> insertIfAbsent(CaptureJournalRecord request, long feeAmountWon,
            long reserveAmountWon, int feeRateBps, int reserveRateBps, Instant recordedAt) {
        return jdbc.query("""
                INSERT INTO settlement_journal
                    (source_order_id, source_attempt_id, seller_id, currency, gross_amount_won, recorded_at,
                     fee_amount_won, reserve_amount_won, fee_rate_bps, reserve_rate_bps)
                VALUES (?, ?, ?, 'KRW', ?, ?, ?, ?, ?, ?)
                ON CONFLICT (source_attempt_id) DO NOTHING
                RETURNING journal_id
                """, resultSet -> resultSet.next()
                ? Optional.of(resultSet.getObject("journal_id", UUID.class)) : Optional.empty(),
                request.orderId(), request.attemptId(), request.sellerId(), request.grossAmountWon(),
                Timestamp.from(recordedAt), feeAmountWon, reserveAmountWon, feeRateBps, reserveRateBps);
    }

 public Optional<CaptureJournalRecord> findByAttemptId(UUID attemptId) {
        return jdbc.query("""
                SELECT source_order_id, source_attempt_id, seller_id, gross_amount_won
                FROM settlement_journal
                WHERE source_attempt_id = ?
                """, resultSet -> resultSet.next()
 ? Optional.of(new CaptureJournalRecord(
                        resultSet.getObject("source_order_id", UUID.class),
                        resultSet.getObject("source_attempt_id", UUID.class),
                        resultSet.getObject("seller_id", UUID.class),
                        resultSet.getLong("gross_amount_won")))
                : Optional.empty(), attemptId);
    }

 public Optional<UUID> insertRefundIfAbsent(RefundJournalRecord request, Instant recordedAt) {
        return jdbc.query("""
                INSERT INTO settlement_journal
                    (source_order_id, source_attempt_id, source_refund_id, seller_id, currency, gross_amount_won, recorded_at)
                VALUES (?, NULL, ?, ?, 'KRW', ?, ?)
                ON CONFLICT (source_refund_id) DO NOTHING
                RETURNING journal_id
                """, resultSet -> resultSet.next()
                ? Optional.of(resultSet.getObject("journal_id", UUID.class)) : Optional.empty(),
                request.orderId(), request.refundId(), request.sellerId(), request.amountWon(),
                Timestamp.from(recordedAt));
    }

 public Optional<RefundJournalRecord> findByRefundId(UUID refundId) {
        return jdbc.query("""
                SELECT source_order_id, source_refund_id, seller_id, gross_amount_won
                FROM settlement_journal
                WHERE source_refund_id = ?
                """, resultSet -> resultSet.next()
 ? Optional.of(new RefundJournalRecord(
                        resultSet.getObject("source_order_id", UUID.class),
                        resultSet.getObject("source_refund_id", UUID.class),
                        resultSet.getObject("seller_id", UUID.class),
                        resultSet.getLong("gross_amount_won")))
                : Optional.empty(), refundId);
    }

    public void insertRefundEntries(UUID journalId, long amountWon, Instant recordedAt) {
        insertRefundEntries(journalId, null, amountWon, recordedAt);
    }

    /**
     * refund 분개. 대변(PSP_CLEARING) = 차변(SELLER_RESERVE + SELLER_PAYABLE).
     *
     * <p>보류금이 남아 있으면 보류에서 먼저 차감한다. 보류는 환불·분쟁 대비로 쌓아둔 돈이라,
     * 환불이 발생하면 지급 예정액보다 먼저 그 재원에서 나가는 것이 맞다. 부족분만
     * SELLER_PAYABLE 에서 차감한다. 보류가 충분하면 지급 가능 잔액은 줄지 않는다.
     */
    public void insertRefundEntries(UUID journalId, UUID sellerId, long amountWon, Instant recordedAt) {
        insertEntry(journalId, sellerId, "PSP_CLEARING", "CREDIT", amountWon, recordedAt);
        long reserveAvailable = sellerId == null ? 0 : reserveBalance(sellerId);
        long fromReserve = Math.min(Math.max(0, reserveAvailable), amountWon);
        long fromPayable = amountWon - fromReserve;
        if (fromReserve > 0) {
            insertEntry(journalId, sellerId, "SELLER_RESERVE", "DEBIT", fromReserve, recordedAt);
        }
        if (fromPayable > 0) {
            insertEntry(journalId, sellerId, "SELLER_PAYABLE", "DEBIT", fromPayable, recordedAt);
        }
    }

    /**
     * 같은 seller 의 지급 요청을 직렬화한다.
     *
     * <p>지급 잔액은 여러 entry 를 합산해 계산하므로, 잔액 확인과 지급 예약 사이에 다른 요청이
     * 끼어들면 같은 잔액을 두 번 예약할 수 있다. seller 단위 advisory transaction lock 으로
     * 그 구간을 직렬화하고, transaction 종료 시 자동 해제한다.
     */
    public void lockSellerPayouts(UUID sellerId) {
        jdbc.query("SELECT pg_advisory_xact_lock(hashtextextended(?, 0))",
                (RowCallbackHandler) resultSet -> { }, "settlement-payout:" + sellerId);
    }

    /** 현재 보류 잔액(대변 − 차변). refund 차감 순서를 정하는 데 쓴다. */
    private long reserveBalance(UUID sellerId) {
        Long reserve = jdbc.queryForObject("""
                SELECT COALESCE(SUM(CASE WHEN entry_side = 'CREDIT' THEN amount_won ELSE -amount_won END), 0)
                FROM settlement_entry WHERE seller_id = ? AND account_code = 'SELLER_RESERVE'
                """, Long.class, sellerId);
        return reserve == null ? 0 : reserve;
    }

    public void insertEntries(UUID journalId, long grossAmountWon, Instant recordedAt) {
        insertEntries(journalId, null, grossAmountWon, 0, 0, recordedAt);
    }

    /**
     * capture 분개를 기록한다.
     *
     * <pre>
     * PSP_CLEARING   차변 gross
     * SELLER_PAYABLE 대변 seller
     * PLATFORM_FEE   대변 fee
     * SELLER_RESERVE 대변 reserve
     * </pre>
     *
     * <p>차변 합(gross)과 대변 합(seller + fee + reserve)이 같아야 한다. 금액이 0 인 계정은
     * entry 를 만들지 않는다(설정이 0 이면 기존 분개와 동일한 형태가 된다).
     */
    public void insertEntries(UUID journalId, UUID sellerId, long grossAmountWon,
            long feeAmountWon, long reserveAmountWon, Instant recordedAt) {
        long sellerPayable = grossAmountWon - feeAmountWon - reserveAmountWon;
        insertEntry(journalId, sellerId, "PSP_CLEARING", "DEBIT", grossAmountWon, recordedAt);
        if (sellerPayable > 0) {
            insertEntry(journalId, sellerId, "SELLER_PAYABLE", "CREDIT", sellerPayable, recordedAt);
        }
        if (feeAmountWon > 0) {
            insertEntry(journalId, sellerId, "PLATFORM_FEE", "CREDIT", feeAmountWon, recordedAt);
        }
        if (reserveAmountWon > 0) {
            insertEntry(journalId, sellerId, "SELLER_RESERVE", "CREDIT", reserveAmountWon, recordedAt);
        }
    }

    /**
     * 지급 분개용 journal 을 만든다. payout 1건당 1 journal 을 unique index 로 보장한다.
     *
     * @return 새로 만들었으면 journalId, 이미 있으면 기존 journalId
     */
    public UUID insertPayoutIfAbsent(UUID payoutId, UUID sellerId, long amountWon, Instant recordedAt) {
        jdbc.update("""
                INSERT INTO settlement_journal
                    (seller_id, currency, gross_amount_won, recorded_at, source_payout_id)
                VALUES (?, 'KRW', ?, ?, ?)
                ON CONFLICT (source_payout_id) WHERE source_payout_id IS NOT NULL DO NOTHING
                """, sellerId, amountWon, Timestamp.from(recordedAt), payoutId);
        List<UUID> ids = jdbc.query("""
                SELECT journal_id FROM settlement_journal WHERE source_payout_id = ?
                """, (resultSet, rowNumber) -> resultSet.getObject("journal_id", UUID.class), payoutId);
        return ids.stream().findFirst().orElse(null);
    }

    public void insertEntry(UUID journalId, UUID sellerId, String accountCode, String side,
            long amountWon, Instant recordedAt) {
        jdbc.update("""
                INSERT INTO settlement_entry
                    (journal_id, seller_id, account_code, entry_side, amount_won, recorded_at)
                VALUES (?, ?, ?, ?, ?, ?)
                """, journalId, sellerId, accountCode, side, amountWon, Timestamp.from(recordedAt));
    }

    public Optional<UUID> insertCheckoutIfAbsent(CheckoutSettlementRecord facts, Instant recordedAt) {
        return jdbc.query("""
                INSERT INTO settlement_journal
                    (source_order_id, source_attempt_id, source_refund_id, seller_id, checkout_id,
                     currency, gross_amount_won, recorded_at)
                VALUES (NULL, ?, NULL, NULL, ?, 'KRW', ?, ?)
                ON CONFLICT DO NOTHING
                RETURNING journal_id
                """, resultSet -> resultSet.next()
                ? Optional.of(resultSet.getObject("journal_id", UUID.class)) : Optional.empty(),
                facts.attemptId(), facts.checkoutId(), facts.totalAmountWon(), Timestamp.from(recordedAt));
    }

    public Optional<CheckoutSettlementRecord> findCheckoutById(UUID checkoutId) {
        return jdbc.query("""
                SELECT journal_id, source_attempt_id, checkout_id, gross_amount_won
                FROM settlement_journal
                WHERE checkout_id = ?
                """, resultSet -> resultSet.next()
                ? Optional.of(new CheckoutSettlementRecord(
                        resultSet.getObject("checkout_id", UUID.class),
                        resultSet.getObject("source_attempt_id", UUID.class),
                        resultSet.getLong("gross_amount_won"),
                        findCheckoutAllocations(resultSet.getObject("journal_id", UUID.class))))
                : Optional.empty(), checkoutId);
    }

    public void insertCheckoutAllocations(UUID journalId, List<CheckoutSettlementAllocation> allocations) {
        for (int i = 0; i < allocations.size(); i++) {
            CheckoutSettlementAllocation allocation = allocations.get(i);
            jdbc.update("""
                    INSERT INTO settlement_seller_allocation
                        (journal_id, order_id, seller_id, amount_won, allocation_ordinal)
                    VALUES (?, ?, ?, ?, ?)
                    """, journalId, allocation.orderId(), allocation.sellerId(), allocation.amountWon(), i);
        }
    }

    private List<CheckoutSettlementAllocation> findCheckoutAllocations(UUID journalId) {
        return jdbc.query("""
                SELECT order_id, seller_id, amount_won
                FROM settlement_seller_allocation
                WHERE journal_id = ?
                ORDER BY allocation_ordinal
                """, (resultSet, rowNum) -> new CheckoutSettlementAllocation(
                resultSet.getObject("order_id", UUID.class),
                resultSet.getObject("seller_id", UUID.class),
                resultSet.getLong("amount_won")), journalId);
    }
    /**
     * 기간 흐름 요약.
     *
     * <p>captured 는 capture gross, reversal 은 refund 분개다. 지급(payout) 분개는 capture 도
     * refund 도 아니므로 제외한다. 지급액은 기간 흐름이 아니라 지급 상태 기계
     * (settlement_payout)의 pending/confirmed 합계와 현재 잔액으로 드러난다. 이렇게 해야
     * 지급이 captured 를 부풀리거나 net 을 두 번 차감하지 않는다.
     *
     * <p>payout journal 은 owner 컬럼이 모두 NULL 이라 capture branch 조건에 걸리므로
     * {@code source_payout_id IS NULL} 을 명시해야 한다. checkout/refund branch 는 owner_xor
     * 제약상 payout 과 겹칠 수 없어 별도 조건이 필요 없다.
     *
     * <p>수수료·보류는 별도 합계로 노출한다. capture gross 는 이미 fee/reserve/seller 몫을 모두
     * 포함하므로 fee·reserve 를 captured 에 더하지 않는다. 두 합계는 기간 안의 계정 이동량
     * (대변 − 차변)이라, refund 로 보류가 차감되면 reserve 합계도 그만큼 줄어든다.
     */
    public SellerStatementTotals sellerStatementTotals(UUID sellerId, Instant from, Instant to) {
        return jdbc.queryForObject("""
                WITH rows AS (
                    SELECT j.gross_amount_won AS amount, FALSE AS refund
                    FROM settlement_journal j
                    WHERE j.seller_id = ? AND j.source_refund_id IS NULL AND j.checkout_id IS NULL
                      AND j.source_payout_id IS NULL
                      AND j.recorded_at >= ? AND j.recorded_at < ?
                    UNION ALL
                    SELECT a.amount_won, FALSE
                    FROM settlement_journal j JOIN settlement_seller_allocation a USING (journal_id)
                    WHERE a.seller_id = ? AND j.checkout_id IS NOT NULL
                      AND j.recorded_at >= ? AND j.recorded_at < ?
                    UNION ALL
                    SELECT j.gross_amount_won, TRUE
                    FROM settlement_journal j
                    WHERE j.seller_id = ? AND j.source_refund_id IS NOT NULL
                      AND j.recorded_at >= ? AND j.recorded_at < ?
                ), period_entries AS (
                    -- row query 와 같은 기준(journal.recorded_at)으로 자른다. entry.recorded_at 을 쓰면
                    -- 경계 시각이 미세하게 달라질 때 row 합과 요약이 어긋날 수 있다.
                    SELECT
                        COALESCE(SUM(CASE WHEN e.account_code = 'PLATFORM_FEE'
                            THEN CASE WHEN e.entry_side = 'CREDIT' THEN e.amount_won ELSE -e.amount_won END
                            ELSE 0 END), 0) AS fee,
                        COALESCE(SUM(CASE WHEN e.account_code = 'SELLER_RESERVE'
                            THEN CASE WHEN e.entry_side = 'CREDIT' THEN e.amount_won ELSE -e.amount_won END
                            ELSE 0 END), 0) AS reserve
                    FROM settlement_entry e JOIN settlement_journal j USING (journal_id)
                    WHERE e.seller_id = ? AND j.recorded_at >= ? AND j.recorded_at < ?
                ), period_flows AS (
                    SELECT COALESCE(SUM(amount) FILTER (WHERE NOT refund), 0) AS captured,
                           COALESCE(-SUM(amount) FILTER (WHERE refund), 0) AS reversal,
                           COALESCE(SUM(CASE WHEN refund THEN -amount ELSE amount END), 0) AS net
                    FROM rows
                ), period_payouts AS (
                    SELECT COALESCE(SUM(j.gross_amount_won), 0) AS paid_out
                    FROM settlement_journal j
                    WHERE j.seller_id = ? AND j.source_payout_id IS NOT NULL
                      AND j.recorded_at >= ? AND j.recorded_at < ?
                )
                SELECT flows.captured, flows.reversal, flows.net, entries.fee, entries.reserve,
                       payouts.paid_out
                FROM period_flows flows
                CROSS JOIN period_entries entries
                CROSS JOIN period_payouts payouts
                """, (rs, n) -> new SellerStatementTotals(
                rs.getBigDecimal("captured").toBigIntegerExact(),
                rs.getBigDecimal("reversal").toBigIntegerExact(),
                rs.getBigDecimal("net").toBigIntegerExact(),
                rs.getBigDecimal("fee").toBigIntegerExact(),
                rs.getBigDecimal("reserve").toBigIntegerExact(),
                rs.getBigDecimal("paid_out").toBigIntegerExact()),
                sellerId, Timestamp.from(from), Timestamp.from(to), sellerId, Timestamp.from(from), Timestamp.from(to),
                sellerId, Timestamp.from(from), Timestamp.from(to),
                sellerId, Timestamp.from(from), Timestamp.from(to),
                sellerId, Timestamp.from(from), Timestamp.from(to));
    }

    /**
     * 판매자가 실제로 받을 잔액을 계정별로 계산한다.
     *
     * <p>statements 의 gross/refund 요약만으로는 "지금 얼마를 받을 수 있는가"를 알 수 없다.
     * 수수료·보류는 대변으로 쌓이고 지급은 차변으로 나가므로, 계정 잔액을 직접 계산한다.
     * 부호 규칙: SELLER_PAYABLE 은 대변(+) − 차변(−) = 지급 가능 잔액.
     * SELLER_RESERVE 는 대변 − 차변 = 보류 잔액. PLATFORM_FEE 는 대변 누적 = 수수료 수익.
     */
    public SellerBalance sellerBalance(UUID sellerId) {
        return jdbc.queryForObject("""
                SELECT
                    COALESCE(SUM(CASE WHEN account_code = 'SELLER_PAYABLE'
                        THEN CASE WHEN entry_side = 'CREDIT' THEN amount_won ELSE -amount_won END
                        ELSE 0 END), 0) AS payable,
                    COALESCE(SUM(CASE WHEN account_code = 'SELLER_RESERVE'
                        THEN CASE WHEN entry_side = 'CREDIT' THEN amount_won ELSE -amount_won END
                        ELSE 0 END), 0) AS reserve,
                    COALESCE(SUM(CASE WHEN account_code = 'PLATFORM_FEE'
                        THEN CASE WHEN entry_side = 'CREDIT' THEN amount_won ELSE -amount_won END
                        ELSE 0 END), 0) AS fee
                FROM settlement_entry
                WHERE seller_id = ?
                """, (resultSet, rowNumber) -> new SellerBalance(
                resultSet.getBigDecimal("payable").toBigIntegerExact(),
                resultSet.getBigDecimal("reserve").toBigIntegerExact(),
                resultSet.getBigDecimal("fee").toBigIntegerExact()), sellerId);
    }

    /**
     * 판매자 명세의 한 page.
     *
     * <p>row 종류는 journal 의 source 로 결정된다.
     * <ul>
     *   <li>{@code ORDER_CAPTURE}: campaign order capture gross. 양수.</li>
     *   <li>{@code CHECKOUT_ALLOCATION}: checkout capture 중 이 seller 의 allocation. 양수.</li>
     *   <li>{@code REFUND_REVERSAL}: refund 분개. 음수.</li>
     *   <li>{@code PAYOUT}: 지급 확정 분개. seller 의 지급 가능 잔액을 줄이므로 음수.</li>
     * </ul>
     *
     * <p>payout journal 은 owner 컬럼(source_order_id/checkout_id/source_refund_id/source_attempt_id)이
     * 모두 NULL 이라 capture branch 조건에 걸린다. 그래서 capture branch 에서 {@code source_payout_id}
     * 를 명시적으로 제외하지 않으면 지급이 ORDER_CAPTURE 로 잘못 분류된다. checkout/refund branch 는
     * owner_xor 제약상 payout 과 겹칠 수 없으므로 별도 제외 조건을 두지 않는다.
     *
     * <p>정렬과 cursor 는 {@code (recorded_at, journal_id)} 그대로다. payout 도 같은 journal
     * key 를 가지므로 page 경계가 흔들리지 않는다.
     */
    public List<SellerStatementRow> sellerStatementRows(UUID sellerId, Instant from, Instant to,
                                                   Instant afterAt, UUID afterId, int limit) {
        return jdbc.query("""
                WITH rows AS (
                    SELECT j.journal_id, j.source_order_id AS order_id, j.source_attempt_id AS source_id,
                           'ORDER_CAPTURE' AS type, j.recorded_at, j.gross_amount_won AS amount
                    FROM settlement_journal j
                    WHERE j.seller_id = ? AND j.source_refund_id IS NULL AND j.checkout_id IS NULL
                      AND j.source_payout_id IS NULL
                      AND j.recorded_at >= ? AND j.recorded_at < ?
                    UNION ALL
                    SELECT j.journal_id, a.order_id, j.checkout_id, 'CHECKOUT_ALLOCATION', j.recorded_at, a.amount_won
                    FROM settlement_journal j JOIN settlement_seller_allocation a USING (journal_id)
                    WHERE a.seller_id = ? AND j.checkout_id IS NOT NULL
                      AND j.recorded_at >= ? AND j.recorded_at < ?
                    UNION ALL
                    SELECT j.journal_id, j.source_order_id, j.source_refund_id, 'REFUND_REVERSAL', j.recorded_at,
                           -j.gross_amount_won
                    FROM settlement_journal j
                    WHERE j.seller_id = ? AND j.source_refund_id IS NOT NULL
                      AND j.recorded_at >= ? AND j.recorded_at < ?
                    UNION ALL
                    SELECT j.journal_id, NULL::uuid, j.source_payout_id, 'PAYOUT', j.recorded_at,
                           -j.gross_amount_won
                    FROM settlement_journal j
                    WHERE j.seller_id = ? AND j.source_payout_id IS NOT NULL
                      AND j.recorded_at >= ? AND j.recorded_at < ?
                )
                SELECT journal_id, order_id, source_id, type, recorded_at, amount
                FROM rows
                WHERE (NOT ? OR (recorded_at, journal_id) > (?, ?))
                ORDER BY recorded_at ASC, journal_id ASC
                LIMIT ?
                """, (rs, n) -> new SellerStatementRow(rs.getObject("journal_id", UUID.class),
                rs.getObject("order_id", UUID.class), rs.getObject("source_id", UUID.class), rs.getString("type"),
                rs.getTimestamp("recorded_at").toInstant(), rs.getLong("amount")),
                sellerId, Timestamp.from(from), Timestamp.from(to), sellerId, Timestamp.from(from), Timestamp.from(to),
                sellerId, Timestamp.from(from), Timestamp.from(to), sellerId, Timestamp.from(from), Timestamp.from(to),
                afterAt != null, afterAt == null ? null : Timestamp.from(afterAt),
                afterId, limit);
    }

}