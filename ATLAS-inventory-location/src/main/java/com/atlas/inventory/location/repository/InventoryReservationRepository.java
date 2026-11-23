package com.atlas.inventory.location.repository;

import com.atlas.inventory.location.persistence.projection.LocationReservationItemRecord;
import com.atlas.inventory.location.persistence.projection.LocationReservationRecord;
import com.atlas.inventory.location.persistence.projection.LocationStockRecord;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

@Repository
public class InventoryReservationRepository {
    private static final RowMapper<LocationStockRecord> STOCK_MAPPER = (rs, row) -> new LocationStockRecord(
            rs.getObject("fulfillment_location_id", UUID.class), rs.getObject("seller_id", UUID.class),
            rs.getString("sku_id"), rs.getLong("initial_capacity"), rs.getLong("available_quantity"),
            rs.getLong("reserved_quantity"), rs.getLong("claimed_quantity"));
    private static final RowMapper<ReservationHeader> RESERVATION_MAPPER = InventoryReservationRepository::mapReservation;
    private final JdbcTemplate jdbc;

    public InventoryReservationRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public int setInitialCapacity(UUID locationId, UUID sellerId, String skuId, long capacity) {
        return jdbc.update("""
                INSERT INTO inventory_location_stock
                    (fulfillment_location_id, seller_id, sku_id, initial_capacity, available_quantity, reserved_quantity, claimed_quantity)
                VALUES (?, ?, ?, ?, ?, 0, 0)
                ON CONFLICT (fulfillment_location_id, seller_id, sku_id) DO UPDATE
                SET initial_capacity = EXCLUDED.initial_capacity, available_quantity = EXCLUDED.available_quantity
                WHERE inventory_location_stock.reserved_quantity = 0 AND inventory_location_stock.claimed_quantity = 0
                """, locationId, sellerId, skuId, capacity, capacity);
    }

    public LocationStockRecord findStock(UUID locationId, UUID sellerId, String skuId) {
        List<LocationStockRecord> rows = jdbc.query("""
                SELECT fulfillment_location_id, seller_id, sku_id, initial_capacity, available_quantity, reserved_quantity, claimed_quantity
                FROM inventory_location_stock WHERE fulfillment_location_id = ? AND seller_id = ? AND sku_id = ?
                """, STOCK_MAPPER, locationId, sellerId, skuId);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    public int insertReservation(UUID reservationId, UUID locationId, UUID orderId, String idempotencyKey,
                                 Instant createdAt, Instant expiresAt) {
        return jdbc.update("""
                INSERT INTO inventory_reservation
                    (reservation_id, fulfillment_location_id, order_id, idempotency_key, status, created_at, expires_at)
                VALUES (?, ?, ?, ?, 'RESERVED', ?, ?) ON CONFLICT DO NOTHING
                """, reservationId, locationId, orderId, idempotencyKey, Timestamp.from(createdAt), Timestamp.from(expiresAt));
    }

    public int insertCancellationTombstone(UUID reservationId, UUID locationId, UUID orderId, String idempotencyKey,
                                           Instant createdAt, Instant expiresAt) {
        return jdbc.update("""
                INSERT INTO inventory_reservation
                    (reservation_id, fulfillment_location_id, order_id, idempotency_key, status, created_at, expires_at)
                VALUES (?, ?, ?, ?, 'CANCELLED', ?, ?) ON CONFLICT DO NOTHING
                """, reservationId, locationId, orderId, idempotencyKey, Timestamp.from(createdAt), Timestamp.from(expiresAt));
    }

    public LocationReservationRecord findByIdempotencyKeyForUpdate(UUID locationId, String key) {
        List<ReservationHeader> rows = jdbc.query("""
                SELECT * FROM inventory_reservation
                WHERE fulfillment_location_id = ? AND idempotency_key = ? FOR UPDATE""", RESERVATION_MAPPER, locationId, key);
        return rows.isEmpty() ? null : withItems(rows.getFirst());
    }
    public LocationReservationRecord findForUpdate(UUID locationId, UUID reservationId) {
        List<ReservationHeader> rows = jdbc.query("""
                SELECT * FROM inventory_reservation
                WHERE fulfillment_location_id = ? AND reservation_id = ? FOR UPDATE""", RESERVATION_MAPPER, locationId, reservationId);
        return rows.isEmpty() ? null : withItems(rows.getFirst());
    }
    public LocationReservationRecord find(UUID locationId, UUID reservationId) {
        List<ReservationHeader> rows = jdbc.query("""
                SELECT * FROM inventory_reservation
                WHERE fulfillment_location_id = ? AND reservation_id = ?""", RESERVATION_MAPPER, locationId, reservationId);
        return rows.isEmpty() ? null : withItems(rows.getFirst());
    }
    public List<LocationReservationRecord> findDueForUpdate(UUID locationId, Instant now, int limit) {
        List<ReservationHeader> rows = jdbc.query("""
                SELECT * FROM inventory_reservation
                WHERE fulfillment_location_id = ? AND status = 'RESERVED' AND expires_at <= ?
                ORDER BY expires_at LIMIT ? FOR UPDATE SKIP LOCKED""", RESERVATION_MAPPER, locationId, Timestamp.from(now), limit);
        List<LocationReservationRecord> result = new ArrayList<>(rows.size());
        for (ReservationHeader row : rows) result.add(withItems(row));
        return result;
    }

    public int insertItem(UUID reservationId, UUID sellerId, String skuId, int quantity) {
        return jdbc.update("""
                INSERT INTO inventory_reservation_item (reservation_id, seller_id, sku_id, quantity)
                VALUES (?, ?, ?, ?)""", reservationId, sellerId, skuId, quantity);
    }
    private List<LocationReservationItemRecord> findItems(UUID reservationId) {
        return jdbc.query("""
                SELECT seller_id, sku_id, quantity FROM inventory_reservation_item
                WHERE reservation_id = ? ORDER BY seller_id, sku_id""",
                (rs, row) -> new LocationReservationItemRecord(rs.getObject("seller_id", UUID.class), rs.getString("sku_id"), rs.getInt("quantity")), reservationId);
    }
    public int reserveAvailable(UUID locationId, UUID sellerId, String skuId, int quantity) {
        return jdbc.update("""
                UPDATE inventory_location_stock SET available_quantity = available_quantity - ?,
                reserved_quantity = reserved_quantity + ? WHERE fulfillment_location_id = ? AND seller_id = ? AND sku_id = ? AND available_quantity >= ?""",
                quantity, quantity, locationId, sellerId, skuId, quantity);
    }
    public int claimReserved(UUID locationId, UUID sellerId, String skuId, int quantity) {
        return jdbc.update("""
                UPDATE inventory_location_stock SET reserved_quantity = reserved_quantity - ?,
                claimed_quantity = claimed_quantity + ? WHERE fulfillment_location_id = ? AND seller_id = ? AND sku_id = ? AND reserved_quantity >= ?""",
                quantity, quantity, locationId, sellerId, skuId, quantity);
    }
    public int releaseReserved(UUID locationId, UUID sellerId, String skuId, int quantity) {
        return jdbc.update("""
                UPDATE inventory_location_stock SET reserved_quantity = reserved_quantity - ?,
                available_quantity = available_quantity + ? WHERE fulfillment_location_id = ? AND seller_id = ? AND sku_id = ? AND reserved_quantity >= ?""",
                quantity, quantity, locationId, sellerId, skuId, quantity);
    }
    public int restoreClaimed(UUID locationId, UUID sellerId, String skuId, int quantity) {
        return jdbc.update("""
                UPDATE inventory_location_stock SET claimed_quantity = claimed_quantity - ?,
                available_quantity = available_quantity + ? WHERE fulfillment_location_id = ? AND seller_id = ? AND sku_id = ? AND claimed_quantity >= ?""",
                quantity, quantity, locationId, sellerId, skuId, quantity);
    }
    public int transitionClaimed(UUID reservationId, String status) {
        return jdbc.update("UPDATE inventory_reservation SET status = ? WHERE reservation_id = ? AND status = 'CLAIMED'", status, reservationId);
    }
    public int insertRestock(String restockKey, UUID reservationId, UUID sellerId, String skuId, int quantity,
                             Instant createdAt) {
        return jdbc.update("""
                INSERT INTO inventory_reservation_restock
                    (restock_key, reservation_id, seller_id, sku_id, quantity, created_at)
                VALUES (?, ?, ?, ?, ?, ?) ON CONFLICT DO NOTHING
                """, restockKey, reservationId, sellerId, skuId, quantity, Timestamp.from(createdAt));
    }
    public List<LocationReservationItemRecord> findRestock(String restockKey) {
        return jdbc.query("""
                SELECT seller_id, sku_id, quantity FROM inventory_reservation_restock
                WHERE restock_key = ? ORDER BY seller_id, sku_id
                """, (rs, row) -> new LocationReservationItemRecord(rs.getObject("seller_id", UUID.class), rs.getString("sku_id"),
                rs.getInt("quantity")), restockKey);
    }
    public UUID findRestockReservation(String restockKey) {
        List<UUID> rows = jdbc.query("SELECT reservation_id FROM inventory_reservation_restock WHERE restock_key = ? LIMIT 1",
                (rs, row) -> rs.getObject("reservation_id", UUID.class), restockKey);
        return rows.isEmpty() ? null : rows.getFirst();
    }
    public int restockedQuantity(UUID reservationId, UUID sellerId, String skuId) {
        Integer total = jdbc.queryForObject("""
                SELECT COALESCE(SUM(quantity), 0) FROM inventory_reservation_restock
                WHERE reservation_id = ? AND seller_id = ? AND sku_id = ?
                """, Integer.class, reservationId, sellerId, skuId);
        return total == null ? 0 : total;
    }
    public int transitionReserved(UUID reservationId, String status) {
        return jdbc.update("UPDATE inventory_reservation SET status = ? WHERE reservation_id = ? AND status = 'RESERVED'", status, reservationId);
    }
    private LocationReservationRecord withItems(ReservationHeader h) {
        return new LocationReservationRecord(h.reservationId(), h.fulfillmentLocationId(), h.orderId(), h.idempotencyKey(),
                h.status(), h.createdAt(), h.expiresAt(), findItems(h.reservationId()));
    }
    private static ReservationHeader mapReservation(ResultSet rs, int row) throws SQLException {
        return new ReservationHeader(rs.getObject("reservation_id", UUID.class),
                rs.getObject("fulfillment_location_id", UUID.class), rs.getObject("order_id", UUID.class),
                rs.getString("idempotency_key"), rs.getString("status"), rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("expires_at").toInstant());
    }

    private record ReservationHeader(UUID reservationId, UUID fulfillmentLocationId, UUID orderId, String idempotencyKey,
                                     String status, Instant createdAt, Instant expiresAt) { }
}
