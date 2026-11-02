package com.atlas.inventory.internal;

import com.atlas.inventory.CreateInventoryPoolCommand;
import com.atlas.inventory.InventoryApi;
import com.atlas.inventory.InventoryUnavailableException;
import com.atlas.inventory.InventoryView;
import com.atlas.inventory.ReservationStatus;
import com.atlas.inventory.ReservationView;
import com.atlas.inventory.ReserveInventoryCommand;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.stereotype.Service;

@Service
public class JdbcInventoryApi implements InventoryApi {

    private static final RowMapper<ReservationView> RESERVATION_MAPPER = (rs, rowNum) ->
            new ReservationView(rs.getObject("order_id", UUID.class),
                    rs.getObject("campaign_id", UUID.class), rs.getString("sku_id"),
                    rs.getInt("quantity"), ReservationStatus.valueOf(rs.getString("status")));

    private static final RowMapper<InventoryView> INVENTORY_MAPPER = (rs, rowNum) ->
            new InventoryView(rs.getObject("campaign_id", UUID.class), rs.getString("sku_id"),
                    rs.getInt("initial_capacity"), rs.getInt("available"),
                    rs.getInt("reserved"), rs.getInt("claimed"));

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;

    public JdbcInventoryApi(JdbcTemplate jdbc, PlatformTransactionManager transactionManager) {
        this.jdbc = Objects.requireNonNull(jdbc);
        this.transactions = new TransactionTemplate(Objects.requireNonNull(transactionManager));
    }

    @Override
    public void createPool(CreateInventoryPoolCommand command) {
        Objects.requireNonNull(command, "command");
        requirePoolKey(command.campaignId(), command.skuId());
        if (command.capacity() < 0) {
            throw new IllegalArgumentException("capacity must not be negative");
        }
        jdbc.update("""
                INSERT INTO inventory_pool (campaign_id, sku_id, initial_capacity, available, reserved, claimed)
                VALUES (?, ?, ?, ?, 0, 0)
                """, command.campaignId(), command.skuId(), command.capacity(), command.capacity());
    }

    @Override
    public ReservationView reserve(ReserveInventoryCommand command) {
        return tryReserve(command).orElseThrow(() ->
                new InventoryUnavailableException("inventory pool is missing or has no available units"));
    }

    @Override
    public Optional<ReservationView> tryReserve(ReserveInventoryCommand command) {
        Objects.requireNonNull(command, "command");
        requirePoolKey(command.campaignId(), command.skuId());
        Objects.requireNonNull(command.orderId(), "orderId");
        if (command.quantity() != 1) {
            throw new IllegalArgumentException("inventory reservations must contain exactly one unit");
        }
        ReservationView reservation = transactions.execute(status -> {
            jdbc.query("SELECT pg_advisory_xact_lock(hashtextextended(?, 0))",
                    (RowCallbackHandler) rs -> { }, command.orderId().toString());
            ReservationView existing = findReservation(command.orderId());
            if (existing != null) {
                if (!existing.campaignId().equals(command.campaignId())
                        || !existing.skuId().equals(command.skuId()) || existing.quantity() != command.quantity()) {
                    throw new IllegalStateException("order already has a reservation for different inventory");
                }
                return existing;
            }
            int changed = jdbc.update("""
                    UPDATE inventory_pool
                    SET available = available - 1, reserved = reserved + 1
                    WHERE campaign_id = ? AND sku_id = ? AND available > 0
                    """, command.campaignId(), command.skuId());
            if (changed != 1) return null;
            jdbc.update("""
                    INSERT INTO inventory_reservation (order_id, campaign_id, sku_id, quantity, status)
                    VALUES (?, ?, ?, 1, 'RESERVED')
                    """, command.orderId(), command.campaignId(), command.skuId());
            return findReservation(command.orderId());
        });
        return Optional.ofNullable(reservation);
    }

    @Override
    public ReservationView claim(UUID orderId) {
        Objects.requireNonNull(orderId, "orderId");
        return Objects.requireNonNull(transactions.execute(status -> {
            ReservationView reservation = lockReservation(orderId);
            if (reservation.status() == ReservationStatus.CLAIMED) {
                return reservation;
            }
            if (reservation.status() != ReservationStatus.RESERVED) {
                throw new IllegalStateException("released inventory cannot be claimed");
            }
            updatePoolForClaim(reservation);
            jdbc.update("UPDATE inventory_reservation SET status = 'CLAIMED' WHERE order_id = ?", orderId);
            return findReservation(orderId);
        }));
    }

    @Override
    public ReservationView release(UUID orderId) {
        Objects.requireNonNull(orderId, "orderId");
        return Objects.requireNonNull(transactions.execute(status -> {
            ReservationView reservation = lockReservation(orderId);
            if (reservation.status() == ReservationStatus.RELEASED) {
                return reservation;
            }
            if (reservation.status() != ReservationStatus.RESERVED) {
                throw new IllegalStateException("claimed inventory cannot be released");
            }
            int changed = jdbc.update("""
                    UPDATE inventory_pool
                    SET available = available + 1, reserved = reserved - 1
                    WHERE campaign_id = ? AND sku_id = ? AND reserved > 0
                    """, reservation.campaignId(), reservation.skuId());
            if (changed != 1) {
                throw new IllegalStateException("reservation and inventory pool are inconsistent");
            }
            jdbc.update("UPDATE inventory_reservation SET status = 'RELEASED' WHERE order_id = ?", orderId);
            return findReservation(orderId);
        }));
    }

    @Override
    public InventoryView find(UUID campaignId, String skuId) {
        requirePoolKey(campaignId, skuId);
        return jdbc.query("""
                SELECT campaign_id, sku_id, initial_capacity, available, reserved, claimed
                FROM inventory_pool WHERE campaign_id = ? AND sku_id = ?
                """, INVENTORY_MAPPER, campaignId, skuId).stream().findFirst().orElse(null);
    }

    private ReservationView lockReservation(UUID orderId) {
        return jdbc.query("""
                SELECT order_id, campaign_id, sku_id, quantity, status
                FROM inventory_reservation WHERE order_id = ? FOR UPDATE
                """, RESERVATION_MAPPER, orderId).stream().findFirst()
                .orElseThrow(() -> new IllegalStateException("reservation does not exist"));
    }

    @Override
    public ReservationView findReservation(UUID orderId) {
        return jdbc.query("""
                SELECT order_id, campaign_id, sku_id, quantity, status
                FROM inventory_reservation WHERE order_id = ?
                """, RESERVATION_MAPPER, orderId).stream().findFirst().orElse(null);
    }

    private void updatePoolForClaim(ReservationView reservation) {
        int changed = jdbc.update("""
                UPDATE inventory_pool SET reserved = reserved - 1, claimed = claimed + 1
                WHERE campaign_id = ? AND sku_id = ? AND reserved > 0
                """, reservation.campaignId(), reservation.skuId());
        if (changed != 1) {
            throw new IllegalStateException("reservation and inventory pool are inconsistent");
        }
    }

    private static void requirePoolKey(UUID campaignId, String skuId) {
        Objects.requireNonNull(campaignId, "campaignId");
        if (skuId == null || skuId.isBlank()) {
            throw new IllegalArgumentException("skuId must not be blank");
        }
    }
}
