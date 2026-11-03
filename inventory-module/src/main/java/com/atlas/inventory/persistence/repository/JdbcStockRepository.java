package com.atlas.inventory.persistence.repository;

import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcStockRepository {

    private final JdbcTemplate jdbc;

    public JdbcStockRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void lockOrder(UUID orderId) {
        jdbc.query("SELECT pg_advisory_xact_lock(hashtextextended(?, 0))",
                (RowCallbackHandler) rs -> { }, orderId.toString());
    }

    public int reserveOne(UUID sellerId, String skuId) {
        return jdbc.update("""
                UPDATE inventory_pool
                SET available = available - 1, reserved = reserved + 1
                WHERE seller_id = ? AND sku_id = ? AND available > 0
                """, sellerId, skuId);
    }

    public int claimOne(UUID sellerId, String skuId) {
        return jdbc.update("""
                UPDATE inventory_pool SET reserved = reserved - 1, claimed = claimed + 1
                WHERE seller_id = ? AND sku_id = ? AND reserved > 0
                """, sellerId, skuId);
    }

    public int releaseOne(UUID sellerId, String skuId) {
        return jdbc.update("""
                UPDATE inventory_pool
                SET available = available + 1, reserved = reserved - 1
                WHERE seller_id = ? AND sku_id = ? AND reserved > 0
                """, sellerId, skuId);
    }
}
