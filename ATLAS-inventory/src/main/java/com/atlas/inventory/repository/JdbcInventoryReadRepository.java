package com.atlas.inventory.repository;

import com.atlas.inventory.persistence.projection.InventoryPoolSummary;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcInventoryReadRepository {
    private final JdbcTemplate jdbc;

    public JdbcInventoryReadRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public InventoryPoolSummary find(UUID sellerId, String skuId) {
        List<InventoryPoolSummary> rows = jdbc.query("""
                SELECT seller_id, sku_id,
                       SUM(initial_capacity) AS initial_capacity,
                       SUM(available) AS available,
                       SUM(reserved) AS reserved,
                       SUM(claimed) AS claimed
                FROM inventory_pool
                WHERE seller_id = ? AND sku_id = ?
                GROUP BY seller_id, sku_id
                """, JdbcInventoryReadRepository::map, sellerId, skuId);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    public List<InventoryPoolSummary> findAll(UUID sellerId) {
        return jdbc.query("""
                SELECT seller_id, sku_id,
                       SUM(initial_capacity) AS initial_capacity,
                       SUM(available) AS available,
                       SUM(reserved) AS reserved,
                       SUM(claimed) AS claimed
                FROM inventory_pool
                WHERE seller_id = ?
                GROUP BY seller_id, sku_id
                ORDER BY sku_id
                """, JdbcInventoryReadRepository::map, sellerId);
    }

    private static InventoryPoolSummary map(ResultSet result, int rowNumber) throws SQLException {
        return new InventoryPoolSummary(
                result.getObject("seller_id", UUID.class),
                result.getString("sku_id"),
                result.getLong("initial_capacity"),
                result.getLong("available"),
                result.getLong("reserved"),
                result.getLong("claimed"));
    }
}
