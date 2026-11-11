package com.atlas.inventory.repository;

import com.atlas.inventory.persistence.projection.InventoryLocationRecord;
import com.atlas.inventory.persistence.projection.InventoryTransferRecord;
import com.atlas.inventory.persistence.projection.InventoryTransferResult;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcLocationRepository {
    private final JdbcTemplate jdbc;

    public JdbcLocationRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<InventoryLocationRecord> create(UUID sellerId, String code, String name, int priority) {
        return jdbc.query("""
                INSERT INTO inventory_location (seller_id, location_code, name, priority, active)
                VALUES (?, ?, ?, ?, TRUE)
                ON CONFLICT (seller_id, location_code) DO NOTHING
                RETURNING location_id, seller_id, location_code, name, priority, active
                """, JdbcLocationRepository::location, sellerId, code, name, priority).stream().findFirst();
    }

    public List<InventoryLocationRecord> find(UUID sellerId) {
        return jdbc.query("""
                SELECT location_id, seller_id, location_code, name, priority, active
                FROM inventory_location
                WHERE seller_id = ?
                ORDER BY priority, location_id
                """, JdbcLocationRepository::location, sellerId);
    }

    public Optional<InventoryLocationRecord> update(
            UUID sellerId, UUID locationId, String name, int priority, boolean active) {
        return jdbc.query("""
                UPDATE inventory_location
                SET name = ?, priority = ?, active = ?
                WHERE seller_id = ? AND location_id = ?
                RETURNING location_id, seller_id, location_code, name, priority, active
                """, JdbcLocationRepository::location, name, priority, active, sellerId, locationId).stream().findFirst();
    }

    public Optional<UUID> advisorySelect(UUID sellerId, Map<String, Integer> requiredQuantities) {
        if (requiredQuantities == null || requiredQuantities.isEmpty()
                || requiredQuantities.values().stream().anyMatch(quantity -> quantity == null || quantity < 1)) {
            return Optional.empty();
        }
        List<Map.Entry<String, Integer>> requirements = requiredQuantities.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .toList();
        String requiredRows = String.join(", ", Collections.nCopies(requirements.size(), "(?, ?)"));
        String query = """
                WITH required(sku_id, required_quantity) AS (
                    VALUES %s
                )
                SELECT location.location_id
                FROM inventory_location location
                CROSS JOIN required
                LEFT JOIN inventory_pool pool
                  ON pool.seller_id = location.seller_id
                 AND pool.location_id = location.location_id
                 AND pool.sku_id = required.sku_id
                WHERE location.seller_id = ?
                  AND location.active
                GROUP BY location.location_id, location.priority
                HAVING COUNT(pool.sku_id) = ?
                   AND BOOL_AND(pool.available >= required.required_quantity)
                ORDER BY location.priority, location.location_id
                LIMIT 1
                """.formatted(requiredRows);
        List<Object> parameters = new ArrayList<>(requirements.size() * 2 + 2);
        for (Map.Entry<String, Integer> requirement : requirements) {
            parameters.add(requirement.getKey());
            parameters.add(requirement.getValue());
        }
        parameters.add(sellerId);
        parameters.add(requirements.size());
        return jdbc.query(query, result -> result.next()
                ? Optional.of(result.getObject("location_id", UUID.class))
                : Optional.empty(), parameters.toArray());
    }

    public Optional<UUID> select(UUID sellerId, Map<String, Integer> requiredQuantities) {
        if (requiredQuantities == null || requiredQuantities.isEmpty()
                || requiredQuantities.values().stream().anyMatch(quantity -> quantity == null || quantity < 1)) {
            return Optional.empty();
        }
        List<String> skuIds = requiredQuantities.keySet().stream().sorted().toList();
        String requestedSkus = String.join(", ", Collections.nCopies(skuIds.size(), "(?)"));
        String insertMissingPools = """
                INSERT INTO inventory_pool (
                    seller_id, location_id, sku_id, initial_capacity, available, reserved, claimed
                )
                SELECT location.seller_id, location.location_id, requested.sku_id, 0, 0, 0, 0
                FROM inventory_location location
                CROSS JOIN (VALUES %s) AS requested(sku_id)
                WHERE location.seller_id = ? AND location.active
                ON CONFLICT (seller_id, location_id, sku_id) DO NOTHING
                """.formatted(requestedSkus);
        List<Object> insertParameters = new ArrayList<>(skuIds.size() + 1);
        insertParameters.addAll(skuIds);
        insertParameters.add(sellerId);
        jdbc.update(insertMissingPools, insertParameters.toArray());

        String placeholders = String.join(",", Collections.nCopies(skuIds.size(), "?"));
        List<Object> parameters = new ArrayList<>();
        parameters.add(sellerId);
        parameters.addAll(skuIds);
        String lockSql = "SELECT pool.location_id, pool.sku_id "
                + "FROM inventory_pool AS pool "
                + "JOIN inventory_location AS location "
                + "ON location.seller_id = pool.seller_id AND location.location_id = pool.location_id "
                + "WHERE pool.seller_id = ? AND pool.sku_id IN (" + placeholders + ") "
                + "AND location.active "
                + "ORDER BY pool.seller_id, pool.sku_id, pool.location_id FOR UPDATE OF pool";
        jdbc.query(lockSql, result -> { }, parameters.toArray());
        return advisorySelect(sellerId, requiredQuantities);
    }

    public InventoryTransferResult transfer(
            UUID sellerId, String skuId, UUID sourceLocationId, UUID destinationLocationId,
            int quantity, String idempotencyKey, String reason) {
        String legacyLockKey = InventoryAdvisoryLockKey.legacyDelimited(
                sellerId.toString(), skuId, idempotencyKey);
        String lockKey = InventoryAdvisoryLockKey.encode(sellerId.toString(), skuId, idempotencyKey);
        String lockSql = "SELECT pg_advisory_xact_lock(hashtextextended(?, 0))";
        jdbc.query(lockSql, result -> { }, legacyLockKey);
        jdbc.query(lockSql, result -> { }, lockKey);
        List<InventoryTransferRecord> prior = jdbc.query("""
                SELECT transfer_id, seller_id, sku_id, source_location_id, destination_location_id,
                       idempotency_key, quantity, reason, created_at
                FROM inventory_transfer
                WHERE seller_id = ? AND sku_id = ? AND idempotency_key = ?
                """, JdbcLocationRepository::transfer, sellerId, skuId, idempotencyKey);
        if (!prior.isEmpty()) {
            InventoryTransferRecord existing = prior.getFirst();
            if (!existing.sourceLocationId().equals(sourceLocationId)
                    || !existing.destinationLocationId().equals(destinationLocationId)
                    || existing.quantity() != quantity || !existing.reason().equals(reason)) {
                return InventoryTransferResult.failure(InventoryTransferResult.Failure.IDEMPOTENCY_CONFLICT);
            }
            return InventoryTransferResult.success(existing);
        }

        jdbc.update("""
                INSERT INTO inventory_pool (
                    seller_id, location_id, sku_id, initial_capacity, available, reserved, claimed
                )
                SELECT seller_id, location_id, ?, 0, 0, 0, 0
                FROM inventory_location
                WHERE seller_id = ? AND location_id IN (?, ?) AND active
                ON CONFLICT (seller_id, location_id, sku_id) DO NOTHING
                """, skuId, sellerId, sourceLocationId, destinationLocationId);
        jdbc.query("""
                SELECT location_id
                FROM inventory_pool
                WHERE seller_id = ? AND sku_id = ? AND location_id IN (?, ?)
                ORDER BY seller_id, sku_id, location_id
                FOR UPDATE
                """, result -> { }, sellerId, skuId, sourceLocationId, destinationLocationId);

        int sourceUpdated = jdbc.update("""
                UPDATE inventory_pool AS pool
                SET initial_capacity = initial_capacity - ?, available = available - ?
                FROM inventory_location AS location
                WHERE pool.seller_id = ? AND pool.sku_id = ? AND pool.location_id = ?
                  AND location.seller_id = pool.seller_id AND location.location_id = pool.location_id
                  AND location.active AND pool.available >= ? AND pool.initial_capacity >= ?
                """, quantity, quantity, sellerId, skuId, sourceLocationId, quantity, quantity);
        if (sourceUpdated != 1) {
            return InventoryTransferResult.failure(InventoryTransferResult.Failure.SOURCE_UNAVAILABLE);
        }

        int destinationUpdated = jdbc.update("""
                UPDATE inventory_pool AS pool
                SET initial_capacity = initial_capacity + ?, available = available + ?
                FROM inventory_location AS location
                WHERE pool.seller_id = ? AND pool.sku_id = ? AND pool.location_id = ?
                  AND location.seller_id = pool.seller_id AND location.location_id = pool.location_id
                  AND location.active
                  AND pool.initial_capacity <= 2147483647 - ?
                  AND pool.available <= 2147483647 - ?
                """, quantity, quantity, sellerId, skuId, destinationLocationId, quantity, quantity);
        if (destinationUpdated != 1) {
            return InventoryTransferResult.failure(InventoryTransferResult.Failure.DESTINATION_UNAVAILABLE);
        }

        InventoryTransferRecord transfer = jdbc.queryForObject("""
                INSERT INTO inventory_transfer (
                    seller_id, sku_id, source_location_id, destination_location_id,
                    idempotency_key, quantity, reason
                )
                VALUES (?, ?, ?, ?, ?, ?, ?)
                RETURNING transfer_id, seller_id, sku_id, source_location_id, destination_location_id,
                          idempotency_key, quantity, reason, created_at
                """, JdbcLocationRepository::transfer, sellerId, skuId, sourceLocationId,
                destinationLocationId, idempotencyKey, quantity, reason);
        return InventoryTransferResult.success(transfer);
    }

    private static InventoryLocationRecord location(ResultSet result, int rowNumber) throws SQLException {
        return new InventoryLocationRecord(
                result.getObject("location_id", UUID.class),
                result.getObject("seller_id", UUID.class),
                result.getString("location_code"),
                result.getString("name"),
                result.getInt("priority"),
                result.getBoolean("active"));
    }

    private static InventoryTransferRecord transfer(ResultSet result, int rowNumber) throws SQLException {
        return new InventoryTransferRecord(
                result.getObject("transfer_id", UUID.class),
                result.getObject("seller_id", UUID.class),
                result.getString("sku_id"),
                result.getObject("source_location_id", UUID.class),
                result.getObject("destination_location_id", UUID.class),
                result.getString("idempotency_key"),
                result.getInt("quantity"),
                result.getString("reason"),
                result.getTimestamp("created_at").toInstant());
    }
}
