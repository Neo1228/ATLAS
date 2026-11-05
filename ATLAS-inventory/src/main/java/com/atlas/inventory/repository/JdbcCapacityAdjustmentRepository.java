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

    public void lock(UUID sellerId, String skuId, String idempotencyKey) {
        jdbc.query("SELECT pg_advisory_xact_lock(hashtextextended(?, 0))",
                resultSet -> null, sellerId + ":" + skuId + ":" + idempotencyKey);
    }

    public Optional<CapacityAdjustmentRecord> find(UUID sellerId, String skuId, String idempotencyKey) {
        try {
            return Optional.of(jdbc.queryForObject("""
                    SELECT a.seller_id, a.sku_id, a.idempotency_key, a.delta, a.reason, a.created_at,
                           a.initial_capacity, a.available, a.reserved, a.claimed
                    FROM inventory_capacity_adjustment a
                    WHERE a.seller_id = ? AND a.sku_id = ? AND a.idempotency_key = ?
                    """, (rs, rowNum) -> map(rs), sellerId, skuId, idempotencyKey));
        } catch (EmptyResultDataAccessException missing) {
            return Optional.empty();
        }
    }

    public Optional<CapacityAdjustmentRecord> record(UUID sellerId, String skuId, String idempotencyKey,
                                             int delta, String reason) {
        try {
            return Optional.of(jdbc.queryForObject("""
                    WITH updated AS (
                        UPDATE inventory_pool
                        SET initial_capacity = initial_capacity + ?, available = available + ?
                        WHERE seller_id = ? AND sku_id = ?
                          AND (? >= 0 OR available >= -CAST(? AS BIGINT))
                          AND (? <= 0 OR (initial_capacity <= 2147483647 - CAST(? AS BIGINT)
                                          AND available <= 2147483647 - CAST(? AS BIGINT)))
                        RETURNING seller_id, sku_id, initial_capacity, available, reserved, claimed
                    )
                    INSERT INTO inventory_capacity_adjustment
                        (seller_id, sku_id, idempotency_key, delta, reason,
                         initial_capacity, available, reserved, claimed)
                    SELECT seller_id, sku_id, ?, ?, ?, initial_capacity, available, reserved, claimed
                    FROM updated
                    RETURNING seller_id, sku_id, idempotency_key, delta, reason, created_at,
                              initial_capacity, available, reserved, claimed
                    """, (rs, rowNum) -> map(rs), delta, delta, sellerId, skuId,
                    delta, delta, delta, delta, delta, idempotencyKey, delta, reason));
        } catch (EmptyResultDataAccessException rejected) {
            return Optional.empty();
        }
    }

    public Optional<InventoryPoolState> findPool(UUID sellerId, String skuId) {
        try {
            return Optional.of(jdbc.queryForObject("""
                    SELECT initial_capacity, available, reserved, claimed
                    FROM inventory_pool WHERE seller_id = ? AND sku_id = ?
                    """, (rs, rowNum) -> new InventoryPoolState(rs.getInt("initial_capacity"), rs.getInt("available"),
                    rs.getInt("reserved"), rs.getInt("claimed")), sellerId, skuId));
        } catch (EmptyResultDataAccessException missing) {
            return Optional.empty();
        }
    }

    private static CapacityAdjustmentRecord map(ResultSet rs) throws SQLException {
        return new CapacityAdjustmentRecord(rs.getObject("seller_id", UUID.class), rs.getString("sku_id"),
                rs.getString("idempotency_key"), rs.getInt("delta"), rs.getString("reason"),
                rs.getTimestamp("created_at").toInstant(), new InventoryPoolState(rs.getInt("initial_capacity"),
                rs.getInt("available"), rs.getInt("reserved"), rs.getInt("claimed")));
    }
}
