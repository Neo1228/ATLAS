package com.atlas.inventory.repository;

import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcStockRepository {
    private final JdbcTemplate jdbc;

    public JdbcStockRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public void lockOrder(UUID orderId) {
        jdbc.query("SELECT pg_advisory_xact_lock(hashtextextended(?, 0))", (RowCallbackHandler) rs -> { }, orderId.toString());
    }

    public int reserve(UUID sellerId, String skuId, int quantity) {
        return jdbc.update("""
                UPDATE inventory_pool
                SET available = available - ?, reserved = reserved + ?
                WHERE seller_id = ? AND sku_id = ? AND available >= ?
                """, quantity, quantity, sellerId, skuId, quantity);
    }

    public int claim(UUID sellerId, String skuId, int quantity) {
        return jdbc.update("""
                UPDATE inventory_pool
                SET reserved = reserved - ?, claimed = claimed + ?
                WHERE seller_id = ? AND sku_id = ? AND reserved >= ?
                """, quantity, quantity, sellerId, skuId, quantity);
    }

    public int release(UUID sellerId, String skuId, int quantity) {
        return jdbc.update("""
                UPDATE inventory_pool
                SET available = available + ?, reserved = reserved - ?
                WHERE seller_id = ? AND sku_id = ? AND reserved >= ?
                """, quantity, quantity, sellerId, skuId, quantity);
    }

    public int reserveOne(UUID sellerId, String skuId) { return reserve(sellerId, skuId, 1); }
    public int claimOne(UUID sellerId, String skuId) { return claim(sellerId, skuId, 1); }
    public int releaseOne(UUID sellerId, String skuId) { return release(sellerId, skuId, 1); }
}
