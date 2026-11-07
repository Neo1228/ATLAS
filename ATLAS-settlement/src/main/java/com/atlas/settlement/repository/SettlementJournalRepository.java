package com.atlas.settlement.repository;

import com.atlas.settlement.persistence.projection.CaptureJournalRecord;
import com.atlas.settlement.persistence.projection.CheckoutSettlementAllocation;
import com.atlas.settlement.persistence.projection.CheckoutSettlementRecord;
import com.atlas.settlement.persistence.projection.RefundJournalRecord;
import com.atlas.settlement.persistence.projection.SellerStatementRow;
import com.atlas.settlement.persistence.projection.SellerStatementTotals;
import java.sql.Timestamp;
import java.sql.ResultSet;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class SettlementJournalRepository {
    private final JdbcTemplate jdbc;

    public SettlementJournalRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

 public Optional<UUID> insertIfAbsent(CaptureJournalRecord request, Instant recordedAt) {
        return jdbc.query("""
                INSERT INTO settlement_journal
                    (source_order_id, source_attempt_id, seller_id, currency, gross_amount_won, recorded_at)
                VALUES (?, ?, ?, 'KRW', ?, ?)
                ON CONFLICT (source_attempt_id) DO NOTHING
                RETURNING journal_id
                """, resultSet -> resultSet.next()
                ? Optional.of(resultSet.getObject("journal_id", UUID.class)) : Optional.empty(),
                request.orderId(), request.attemptId(), request.sellerId(), request.grossAmountWon(),
                Timestamp.from(recordedAt));
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
        insertEntry(journalId, "SELLER_PAYABLE", "DEBIT", amountWon, recordedAt);
        insertEntry(journalId, "PSP_CLEARING", "CREDIT", amountWon, recordedAt);
    }

    public void insertEntries(UUID journalId, long grossAmountWon, Instant recordedAt) {
        insertEntry(journalId, "PSP_CLEARING", "DEBIT", grossAmountWon, recordedAt);
        insertEntry(journalId, "SELLER_PAYABLE", "CREDIT", grossAmountWon, recordedAt);
    }

    private void insertEntry(UUID journalId, String accountCode, String side, long amountWon, Instant recordedAt) {
        jdbc.update("""
                INSERT INTO settlement_entry (journal_id, account_code, entry_side, amount_won, recorded_at)
                VALUES (?, ?, ?, ?, ?)
                """, journalId, accountCode, side, amountWon, Timestamp.from(recordedAt));
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
    public SellerStatementTotals sellerStatementTotals(UUID sellerId, Instant from, Instant to) {
        return jdbc.queryForObject("""
                WITH rows AS (
                    SELECT j.gross_amount_won AS amount, FALSE AS refund
                    FROM settlement_journal j
                    WHERE j.seller_id = ? AND j.source_refund_id IS NULL AND j.checkout_id IS NULL
                      AND j.recorded_at >= ? AND j.recorded_at < ?
                    UNION ALL
                    SELECT a.amount_won, FALSE
                    FROM settlement_journal j JOIN settlement_seller_allocation a USING (journal_id)
                    WHERE a.seller_id = ? AND j.checkout_id IS NOT NULL AND j.recorded_at >= ? AND j.recorded_at < ?
                    UNION ALL
                    SELECT j.gross_amount_won, TRUE
                    FROM settlement_journal j
                    WHERE j.seller_id = ? AND j.source_refund_id IS NOT NULL
                      AND j.recorded_at >= ? AND j.recorded_at < ?
                )
                SELECT COALESCE(SUM(amount) FILTER (WHERE NOT refund), 0) AS captured,
                       COALESCE(-SUM(amount) FILTER (WHERE refund), 0) AS reversal,
                       COALESCE(SUM(CASE WHEN refund THEN -amount ELSE amount END), 0) AS net
                FROM rows
                """, (rs, n) -> new SellerStatementTotals(
                rs.getBigDecimal("captured").toBigIntegerExact(),
                rs.getBigDecimal("reversal").toBigIntegerExact(),
                rs.getBigDecimal("net").toBigIntegerExact()),
                sellerId, Timestamp.from(from), Timestamp.from(to), sellerId, Timestamp.from(from), Timestamp.from(to),
                sellerId, Timestamp.from(from), Timestamp.from(to));
    }

    public List<SellerStatementRow> sellerStatementRows(UUID sellerId, Instant from, Instant to,
                                                   Instant afterAt, UUID afterId, int limit) {
        return jdbc.query("""
                WITH rows AS (
                    SELECT j.journal_id, j.source_order_id AS order_id, j.source_attempt_id AS source_id,
                           'ORDER_CAPTURE' AS type, j.recorded_at, j.gross_amount_won AS amount
                    FROM settlement_journal j
                    WHERE j.seller_id = ? AND j.source_refund_id IS NULL AND j.checkout_id IS NULL
                      AND j.recorded_at >= ? AND j.recorded_at < ?
                    UNION ALL
                    SELECT j.journal_id, a.order_id, j.checkout_id, 'CHECKOUT_ALLOCATION', j.recorded_at, a.amount_won
                    FROM settlement_journal j JOIN settlement_seller_allocation a USING (journal_id)
                    WHERE a.seller_id = ? AND j.checkout_id IS NOT NULL AND j.recorded_at >= ? AND j.recorded_at < ?
                    UNION ALL
                    SELECT j.journal_id, j.source_order_id, j.source_refund_id, 'REFUND_REVERSAL', j.recorded_at,
                           -j.gross_amount_won
                    FROM settlement_journal j
                    WHERE j.seller_id = ? AND j.source_refund_id IS NOT NULL
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
                sellerId, Timestamp.from(from), Timestamp.from(to), afterAt != null, afterAt == null ? null : Timestamp.from(afterAt),
                afterId, limit);
    }

}
