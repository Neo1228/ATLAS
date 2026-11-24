package com.atlas.order.repository;

import com.atlas.order.persistence.projection.ExistingReorder;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class OrderReorderRepository {
    private static final String INSERT_IF_ABSENT = """
            INSERT INTO order_reorder_request
                (seller_id, buyer_ref, idempotency_key, source_order_id, confirm_merge, created_at)
            VALUES (?, ?, ?, ?, ?, ?)
            ON CONFLICT (seller_id, buyer_ref, idempotency_key) DO NOTHING
            """;

    private final JdbcTemplate jdbc;

    public OrderReorderRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<ExistingReorder> find(UUID sellerId, String buyerRef, String idempotencyKey) {
        List<ExistingReorder> rows = jdbc.query("""
                SELECT source_order_id, confirm_merge
                FROM order_reorder_request
                WHERE seller_id = ? AND buyer_ref = ? AND idempotency_key = ?
                """, (result, row) -> new ExistingReorder(
                result.getObject("source_order_id", UUID.class), result.getBoolean("confirm_merge")),
                sellerId, buyerRef, idempotencyKey);
        return rows.stream().findFirst();
    }

    public boolean insertIfAbsent(UUID sellerId, String buyerRef, String idempotencyKey,
                                  UUID sourceOrderId, boolean confirmMerge, Instant createdAt) {
        return jdbc.update(INSERT_IF_ABSENT, sellerId, buyerRef, idempotencyKey,
                sourceOrderId, confirmMerge, Timestamp.from(createdAt)) == 1;
    }

}
