package com.atlas.settlement.repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Holds unresolved refund obligations against Seller payouts without prematurely posting the ledger. */
@Repository
public class SettlementRefundReservationRepository {
    private final JdbcTemplate jdbc;

    public SettlementRefundReservationRepository(JdbcTemplate jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc);
    }

    public RefundReservation reserve(UUID refundId, UUID orderId, UUID sellerId, long amountWon, Instant now) {
        Objects.requireNonNull(refundId);
        Objects.requireNonNull(orderId);
        Objects.requireNonNull(sellerId);
        Objects.requireNonNull(now);
        if (amountWon <= 0) throw new IllegalArgumentException("Refund reservation amount must be positive");
        jdbc.update("""
                INSERT INTO settlement_refund_reservation (
                    refund_id, order_id, seller_id, amount_won, status, created_at, updated_at)
                VALUES (?, ?, ?, ?, 'RESERVED', ?, ?)
                ON CONFLICT (refund_id) DO NOTHING
                """, refundId, orderId, sellerId, amountWon, Timestamp.from(now), Timestamp.from(now));
        RefundReservation existing = find(refundId)
                .orElseThrow(() -> new IllegalStateException("Refund reservation was not persisted"));
        return existing;
    }

    public Optional<RefundReservation> find(UUID refundId) {
        return jdbc.query("""
                SELECT refund_id, order_id, seller_id, amount_won, status, created_at, updated_at
                FROM settlement_refund_reservation WHERE refund_id = ?
                """, (row, number) -> map(row), refundId).stream().findFirst();
    }

    public long pendingAmount(UUID sellerId) {
        Long amount = jdbc.queryForObject("""
                SELECT COALESCE(SUM(amount_won), 0)
                FROM settlement_refund_reservation
                WHERE seller_id = ? AND status = 'RESERVED'
                """, Long.class, sellerId);
        return amount == null ? 0 : amount;
    }

    public ReservationMetrics metricsSnapshot() {
        return jdbc.queryForObject("""
                SELECT count(*) AS reservation_count, COALESCE(SUM(amount_won), 0) AS amount_won
                FROM settlement_refund_reservation WHERE status = 'RESERVED'
                """, (row, number) -> new ReservationMetrics(
                row.getLong("reservation_count"), row.getLong("amount_won")));
    }

    public boolean markPosted(UUID refundId, Instant now) {
        int updated = jdbc.update("""
                UPDATE settlement_refund_reservation
                SET status = 'POSTED', updated_at = ?
                WHERE refund_id = ? AND status = 'RESERVED'
                """, Timestamp.from(now), refundId);
        if (updated == 1) return true;
        return find(refundId).map(row -> "POSTED".equals(row.status())).orElse(false);
    }

    public boolean release(UUID refundId, Instant now) {
        int updated = jdbc.update("""
                UPDATE settlement_refund_reservation
                SET status = 'RELEASED', updated_at = ?
                WHERE refund_id = ? AND status = 'RESERVED'
                """, Timestamp.from(now), refundId);
        if (updated == 1) return true;
        return find(refundId).map(row -> "RELEASED".equals(row.status())).orElse(false);
    }

    private static RefundReservation map(ResultSet row) throws SQLException {
        return new RefundReservation(row.getObject("refund_id", UUID.class),
                row.getObject("order_id", UUID.class), row.getObject("seller_id", UUID.class),
                row.getLong("amount_won"), row.getString("status"),
                row.getTimestamp("created_at").toInstant(), row.getTimestamp("updated_at").toInstant());
    }

    public record RefundReservation(UUID refundId, UUID orderId, UUID sellerId,
            long amountWon, String status, Instant createdAt, Instant updatedAt) { }

    public record ReservationMetrics(long reservationCount, long amountWon) { }
}
