package com.atlas.inventory.repository;

import com.atlas.inventory.dto.response.InventoryAdjustmentResponse;
import com.atlas.inventory.dto.response.InventoryPoolResponse;
import com.atlas.inventory.exception.InventoryException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.UUID;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcCapacityAdjustmentRepository {
    private final JdbcTemplate jdbc;

    public JdbcCapacityAdjustmentRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public void lock(UUID sellerId, String skuId, String key) {
        jdbc.query("SELECT pg_advisory_xact_lock(hashtextextended(?, 0))", rs -> { },
                sellerId + ":" + skuId + ":" + key);
    }

    public InventoryAdjustmentResponse find(UUID sellerId, String skuId, String key) {
        try {
            return jdbc.queryForObject("""
                    SELECT seller_id, sku_id, idempotency_key, delta, reason, initial_capacity,
                           available, reserved, claimed, created_at
                    FROM inventory_capacity_adjustment
                    WHERE seller_id = ? AND sku_id = ? AND idempotency_key = ?
                    """, (rs, row) -> map(rs), sellerId, skuId, key);
        } catch (EmptyResultDataAccessException absent) {
            return null;
        }
    }

    public InventoryAdjustmentResponse record(UUID sellerId, String skuId, String key,
            int delta, String reason) {
        int changed = jdbc.update("""
                UPDATE inventory_pool
                SET initial_capacity = initial_capacity + ?, available = available + ?
                WHERE seller_id = ? AND sku_id = ? AND (? > 0 OR available >= -?)
                """, delta, delta, sellerId, skuId, delta, delta);
        if (changed != 1) {
            throw new InventoryException(InventoryException.Code.INSUFFICIENT_STOCK,
                    "inventory pool is missing or available stock is insufficient");
        }
        return jdbc.queryForObject("""
                INSERT INTO inventory_capacity_adjustment
                    (seller_id, sku_id, idempotency_key, delta, reason,
                     initial_capacity, available, reserved, claimed)
                SELECT p.seller_id, p.sku_id, ?, ?, ?, p.initial_capacity, p.available, p.reserved, p.claimed
                FROM inventory_pool p WHERE p.seller_id = ? AND p.sku_id = ?
                RETURNING seller_id, sku_id, idempotency_key, delta, reason, initial_capacity,
                          available, reserved, claimed, created_at
                """, (rs, row) -> map(rs), key, delta, reason, sellerId, skuId);
    }

    private static InventoryAdjustmentResponse map(ResultSet rs) throws SQLException {
        UUID sellerId = rs.getObject("seller_id", UUID.class);
        String sku = rs.getString("sku_id");
        InventoryPoolResponse pool = new InventoryPoolResponse(sellerId, sku,
                rs.getInt("initial_capacity"), rs.getInt("available"),
                rs.getInt("reserved"), rs.getInt("claimed"));
        return new InventoryAdjustmentResponse(sellerId, sku, rs.getString("idempotency_key"),
                rs.getInt("delta"), rs.getString("reason"), pool,
                rs.getTimestamp("created_at").toInstant());
    }
}
