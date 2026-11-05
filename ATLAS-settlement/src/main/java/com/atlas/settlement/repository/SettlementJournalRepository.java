package com.atlas.settlement.repository;

import com.atlas.settlement.persistence.projection.CaptureJournalRecord;
import com.atlas.settlement.persistence.projection.RefundJournalRecord;
import java.sql.Timestamp;
import java.time.Instant;
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
}
