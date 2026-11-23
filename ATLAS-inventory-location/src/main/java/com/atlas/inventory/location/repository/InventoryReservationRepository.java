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
    public List<ItemRecord> findRestock(String restockKey) {
        return jdbc.query("""
                SELECT seller_id, sku_id, quantity FROM inventory_reservation_restock
                WHERE restock_key = ? ORDER BY seller_id, sku_id
                """, (rs, row) -> new ItemRecord(rs.getObject("seller_id", UUID.class), rs.getString("sku_id"),
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