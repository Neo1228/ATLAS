package com.atlas.inventory.repository;

import com.atlas.inventory.persistence.projection.CapacityAdjustmentRecord;
import com.atlas.inventory.persistence.projection.InventoryPoolState;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcCapacityAdjustmentRepository {
    private final JdbcTemplate jdbc;

    public JdbcCapacityAdjustmentRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void lock(UUID seller, String sku, String key) {
        lockAdjustmentKey(seller, "DEFAULT", sku, key);
    }

    public void lock(UUID seller, UUID location, String sku, String key) {
        // DEFAULT 위치의 UUID가 늦게 생겨도 기존 API와 같은 잠금을 사용한다.
        lockAdjustmentKey(seller, locationLockScope(seller, location), sku, key);
    }

    private String locationLockScope(UUID seller, UUID location) {
        try {
            String locationCode = jdbc.queryForObject(
                    "SELECT location_code FROM inventory_location WHERE seller_id = ? AND location_id = ?",
                    String.class, seller, location);
            return "DEFAULT".equals(locationCode) ? "DEFAULT" : location.toString();
        } catch (EmptyResultDataAccessException exception) {
            return location.toString();
        }
    }

    private void lockAdjustmentKey(UUID seller, String locationScope, String sku, String key) {
        jdbc.query(
                "SELECT pg_advisory_xact_lock(hashtextextended(?,0))",
                resultSet -> { },
                seller + ":" + locationScope + ":" + sku + ":" + key);
    }

    public Optional<CapacityAdjustmentRecord> find(UUID seller, String sku, String key) {
        UUID location = defaultLocation(seller);
        return location == null ? Optional.empty() : find(seller, location, sku, key);
    }

    public Optional<CapacityAdjustmentRecord> find(UUID seller, UUID location, String sku, String key) {
        try {
            return Optional.of(jdbc.queryForObject("""
                    SELECT a.seller_id, a.sku_id, a.idempotency_key, a.delta, a.reason, a.created_at,
                           a.initial_capacity, a.available, a.reserved, a.claimed
                    FROM inventory_capacity_adjustment AS a
                    WHERE a.seller_id = ? AND a.location_id = ? AND a.sku_id = ? AND a.idempotency_key = ?
                    """, (resultSet, rowNumber) -> map(resultSet), seller, location, sku, key));
        } catch (EmptyResultDataAccessException exception) {
            return Optional.empty();
        }
    }

    public Optional<CapacityAdjustmentRecord> record(
            UUID seller, String sku, String key, int delta, String reason) {
        UUID location = defaultLocation(seller);
        return location == null ? Optional.empty() : record(seller, location, sku, key, delta, reason);
    }

    public Optional<CapacityAdjustmentRecord> record(
            UUID seller, UUID location, String sku, String key, int delta, String reason) {
        try {
            return Optional.of(jdbc.queryForObject("""
                    WITH updated AS (
                        UPDATE inventory_pool
                        SET initial_capacity = initial_capacity + ?,
                            available = available + ?
                        WHERE seller_id = ? AND location_id = ? AND sku_id = ?
                          AND (? >= 0 OR available >= -CAST(? AS BIGINT))
                          AND (? <= 0 OR (
                              initial_capacity <= 2147483647 - CAST(? AS BIGINT)
                              AND available <= 2147483647 - CAST(? AS BIGINT)
                          ))
                        RETURNING seller_id, location_id, sku_id, initial_capacity, available, reserved, claimed
                    )
                    INSERT INTO inventory_capacity_adjustment (
                        seller_id, location_id, sku_id, idempotency_key, delta, reason,
                        initial_capacity, available, reserved, claimed
                    )
                    SELECT seller_id, location_id, sku_id, ?, ?, ?, initial_capacity, available, reserved, claimed
                    FROM updated
                    RETURNING seller_id, sku_id, idempotency_key, delta, reason, created_at,
                              initial_capacity, available, reserved, claimed
                    """, (resultSet, rowNumber) -> map(resultSet),
                    delta, delta, seller, location, sku, delta, delta, delta, delta, delta,
                    key, delta, reason));
        } catch (EmptyResultDataAccessException exception) {
            return Optional.empty();
        }
    }

    public void ensureLocationPool(UUID sellerId, UUID locationId, String skuId) {
        jdbc.update("""
                INSERT INTO inventory_pool (
                    seller_id, location_id, sku_id, initial_capacity, available, reserved, claimed
                )
                SELECT location.seller_id, location.location_id, ?, 0, 0, 0, 0
                FROM inventory_location AS location
                WHERE location.seller_id = ? AND location.location_id = ?
                  AND EXISTS (
                      SELECT 1 FROM inventory_pool AS existing_pool
                      WHERE existing_pool.seller_id = ? AND existing_pool.sku_id = ?
                  )
                ON CONFLICT (seller_id, location_id, sku_id) DO NOTHING
                """, skuId, sellerId, locationId, sellerId, skuId);
    }

    public Optional<InventoryPoolState> findPool(UUID seller, String sku) {
        UUID location = defaultLocation(seller);
        return location == null ? Optional.empty() : findPool(seller, location, sku);
    }

    public Optional<InventoryPoolState> findPool(UUID seller, UUID location, String sku) {
        try {
            return Optional.of(jdbc.queryForObject("""
                    SELECT initial_capacity, available, reserved, claimed
                    FROM inventory_pool
                    WHERE seller_id = ? AND location_id = ? AND sku_id = ?
                    """,
                    (resultSet, rowNumber) -> new InventoryPoolState(
                            resultSet.getInt(1), resultSet.getInt(2),
                            resultSet.getInt(3), resultSet.getInt(4)),
                    seller, location, sku));
        } catch (EmptyResultDataAccessException exception) {
            return Optional.empty();
        }
    }

    private UUID defaultLocation(UUID seller) {
        try {
            return jdbc.queryForObject(
                    "SELECT location_id FROM inventory_location WHERE seller_id = ? AND location_code = 'DEFAULT'",
                    UUID.class,
                    seller);
        } catch (EmptyResultDataAccessException exception) {
            return null;
        }
    }

    private static CapacityAdjustmentRecord map(ResultSet resultSet) throws SQLException {
        return new CapacityAdjustmentRecord(
                resultSet.getObject("seller_id", UUID.class),
                resultSet.getString("sku_id"),
                resultSet.getString("idempotency_key"),
                resultSet.getInt("delta"),
                resultSet.getString("reason"),
                resultSet.getTimestamp("created_at").toInstant(),
                new InventoryPoolState(
                        resultSet.getInt("initial_capacity"),
                        resultSet.getInt("available"),
                        resultSet.getInt("reserved"),
                        resultSet.getInt("claimed")));
    }
}
