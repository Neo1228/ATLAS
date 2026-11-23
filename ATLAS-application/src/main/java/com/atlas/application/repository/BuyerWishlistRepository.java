package com.atlas.application.repository;

import com.atlas.application.persistence.projection.SavedProduct;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class BuyerWishlistRepository {
    private final JdbcTemplate jdbc;

    public BuyerWishlistRepository(JdbcTemplate jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
    }

    public void saveIfAbsent(String buyerRef, UUID productId) {
        jdbc.update("""
                INSERT INTO buyer_wishlist_item (buyer_ref, product_id)
                VALUES (?, ?)
                ON CONFLICT (buyer_ref, product_id) DO NOTHING
                """, buyerRef, productId);
    }

    public Optional<SavedProduct> find(String buyerRef, UUID productId) {
        return jdbc.query("""
                SELECT product_id, saved_at
                FROM buyer_wishlist_item
                WHERE buyer_ref = ? AND product_id = ?
                """, (rs, rowNum) -> new SavedProduct(
                rs.getObject("product_id", UUID.class), rs.getTimestamp("saved_at").toInstant()),
                buyerRef, productId).stream().findFirst();
    }

    public List<SavedProduct> list(String buyerRef, int limit, int offset) {
        return jdbc.query("""
                SELECT product_id, saved_at
                FROM buyer_wishlist_item
                WHERE buyer_ref = ?
                ORDER BY saved_at DESC, product_id DESC
                LIMIT ? OFFSET ?
                """, (rs, rowNum) -> new SavedProduct(
                rs.getObject("product_id", UUID.class), rs.getTimestamp("saved_at").toInstant()),
                buyerRef, limit, offset);
    }

    public void remove(String buyerRef, UUID productId) {
        jdbc.update("DELETE FROM buyer_wishlist_item WHERE buyer_ref = ? AND product_id = ?", buyerRef, productId);
    }
}
