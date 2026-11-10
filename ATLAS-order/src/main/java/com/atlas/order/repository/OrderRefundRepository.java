package com.atlas.order.repository;

import com.atlas.order.persistence.projection.RefundHeader;
import com.atlas.order.persistence.projection.RefundItem;
import com.atlas.order.persistence.projection.RefundRecord;
import com.atlas.order.persistence.projection.RefundSummary;
import com.atlas.order.persistence.state.OrderRefundStatus;
import com.atlas.order.persistence.state.OrderRefundPaymentStatus;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.stereotype.Repository;

@Repository
public class OrderRefundRepository {
    private final JdbcTemplate jdbc;

    public OrderRefundRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void lockIdempotencyKey(String idempotencyKey) {
        jdbc.query("SELECT pg_advisory_xact_lock(hashtextextended(?, 0))",
                (RowCallbackHandler) rs -> { }, idempotencyKey);
    }

    public Optional<RefundRecord> findByIdempotencyKey(String idempotencyKey) {
        List<RefundHeader> rows = jdbc.query("""
                SELECT refund_id, order_id, seller_id, idempotency_key, amount_won, refund_status, created_at
                FROM order_refund_request
                WHERE idempotency_key = ?
                """, (resultSet, rowNum) -> new RefundHeader(
                resultSet.getObject("refund_id", UUID.class), resultSet.getObject("order_id", UUID.class),
                resultSet.getObject("seller_id", UUID.class), resultSet.getString("idempotency_key"),
                resultSet.getLong("amount_won"), OrderRefundStatus.valueOf(resultSet.getString("refund_status")),
                resultSet.getTimestamp("created_at").toInstant()), idempotencyKey);
        if (rows.isEmpty()) return Optional.empty();
        RefundHeader row = rows.get(0);
        return Optional.of(new RefundRecord(row.refundId(), row.orderId(), row.sellerId(), row.idempotencyKey(),
                row.recordedAmountWon(), row.status(), row.createdAt(), findItems(row.refundId())));
    }

    public Map<UUID, Integer> refundedQuantitiesByOrder(UUID orderId) {
        return jdbc.query("""
                SELECT item.order_item_id, SUM(item.quantity) AS refunded_quantity
                FROM order_refund_request request
                JOIN order_refund_request_item item ON item.refund_id = request.refund_id
                WHERE request.order_id = ? AND request.payment_refund_status <> 'FAILED'
                GROUP BY item.order_item_id
                """, resultSet -> {
            Map<UUID, Integer> quantities = new HashMap<>();
            while (resultSet.next()) {
                quantities.put(resultSet.getObject("order_item_id", UUID.class),
                        Math.toIntExact(resultSet.getLong("refunded_quantity")));
            }
            return quantities;
        }, orderId);
    }
    public Map<UUID, Integer> preShipmentRefundedQuantitiesByOrder(UUID orderId) {
        return jdbc.query("""
                SELECT item.order_item_id, SUM(item.quantity) AS refunded_quantity
                FROM order_refund_request request
                JOIN order_refund_request_item item ON item.refund_id = request.refund_id
                WHERE request.order_id = ? AND request.payment_refund_status <> 'FAILED'
                  AND NOT EXISTS (
                    SELECT 1 FROM order_return_item returned
                    WHERE returned.refund_id = item.refund_id
                      AND returned.order_item_id = item.order_item_id
                  )
                GROUP BY item.order_item_id
                """, resultSet -> {
            Map<UUID, Integer> quantities = new HashMap<>();
            while (resultSet.next()) quantities.put(resultSet.getObject("order_item_id", UUID.class),
                    Math.toIntExact(resultSet.getLong("refunded_quantity")));
            return quantities;
        }, orderId);
    }

    public Map<UUID, Integer> completedRefundedQuantitiesByOrder(UUID orderId) {
        return jdbc.query("""
                SELECT item.order_item_id, SUM(item.quantity) AS refunded_quantity
                FROM order_refund_request request
                JOIN order_refund_request_item item ON item.refund_id = request.refund_id
                WHERE request.order_id = ? AND request.payment_refund_status IN ('NOT_APPLICABLE', 'COMPLETED')
                GROUP BY item.order_item_id
                """, resultSet -> {
            Map<UUID, Integer> quantities = new HashMap<>();
            while (resultSet.next()) quantities.put(resultSet.getObject("order_item_id", UUID.class),
                    Math.toIntExact(resultSet.getLong("refunded_quantity")));
            return quantities;
        }, orderId);
    }

    public OrderRefundPaymentStatus paymentStatus(UUID refundId) {
        return jdbc.queryForObject("""
                SELECT payment_refund_status FROM order_refund_request WHERE refund_id = ?
                """, (resultSet, rowNum) -> OrderRefundPaymentStatus.valueOf(
                resultSet.getString("payment_refund_status")), refundId);
    }

    public void linkPaymentOperation(UUID refundId, UUID paymentOperationId) {
        int updated = jdbc.update("""
                UPDATE order_refund_request SET payment_refund_operation_id = ?
                WHERE refund_id = ?
                  AND (payment_refund_operation_id IS NULL OR payment_refund_operation_id = ?)
                """, paymentOperationId, refundId, paymentOperationId);
        if (updated != 1) throw new IllegalStateException("Refund request is linked to another payment operation");
    }

    public boolean updatePaymentStatus(UUID refundId, OrderRefundPaymentStatus status) {
        return jdbc.update("""
                UPDATE order_refund_request SET payment_refund_status = ?
                WHERE refund_id = ? AND payment_refund_status <> 'COMPLETED'
                """, status.name(), refundId) == 1;
    }

    public void updateRefundStatus(UUID refundId, OrderRefundStatus status) {
        int updated = jdbc.update("""
                UPDATE order_refund_request SET refund_status = ?
                WHERE refund_id = ?
                """, status.name(), refundId);
        if (updated != 1) throw new IllegalStateException("Refund request disappeared during status update");
    }

    public Optional<RefundRecord> findByPaymentOperationId(UUID paymentOperationId) {
        List<String> keys = jdbc.query("""
                SELECT idempotency_key FROM order_refund_request
                WHERE payment_refund_operation_id = ?
                FOR UPDATE
                """, (resultSet, rowNum) -> resultSet.getString("idempotency_key"), paymentOperationId);
        return keys.stream().findFirst().flatMap(this::findByIdempotencyKey);
    }
    public Optional<RefundSummary> findSummary(UUID orderId) {
        return jdbc.query("""
                SELECT seller_id, recorded_amount_won, refund_status, updated_at
                FROM order_refund_summary
                WHERE order_id = ?
                """, resultSet -> resultSet.next()
                ? Optional.of(new RefundSummary(orderId, resultSet.getObject("seller_id", UUID.class),
                        resultSet.getLong("recorded_amount_won"),
                        OrderRefundStatus.valueOf(resultSet.getString("refund_status")),
                        resultSet.getTimestamp("updated_at").toInstant()))
                : Optional.empty(), orderId);
    }

    public boolean isFullyRefunded(UUID orderId) {
        return findSummary(orderId).map(summary -> summary.status() == OrderRefundStatus.FULLY_RECORDED)
                .orElse(false);
    }

    public UUID insertRequest(UUID orderId, UUID sellerId, String idempotencyKey,
                              long recordedAmountWon, OrderRefundStatus status, Instant createdAt) {
        return jdbc.queryForObject("""
                INSERT INTO order_refund_request
                (order_id, seller_id, idempotency_key, amount_won, refund_status, payment_refund_status, created_at)
                VALUES (?, ?, ?, ?, ?, 'PENDING', ?)
                RETURNING refund_id
                """, UUID.class, orderId, sellerId, idempotencyKey, recordedAmountWon, status.name(),
                Timestamp.from(createdAt));
    }

    public void insertRequestItem(UUID refundId, UUID orderItemId, int quantity) {
        jdbc.update("""
                INSERT INTO order_refund_request_item (refund_id, order_item_id, quantity)
                VALUES (?, ?, ?)
                """, refundId, orderItemId, quantity);
    }

    public void upsertSummary(UUID orderId, UUID sellerId, long recordedAmountWon,
                              OrderRefundStatus status, Instant updatedAt) {
        jdbc.update("""
                INSERT INTO order_refund_summary (order_id, seller_id, recorded_amount_won, refund_status, updated_at)
                VALUES (?, ?, ?, ?, ?)
                ON CONFLICT (order_id) DO UPDATE SET
                recorded_amount_won = EXCLUDED.recorded_amount_won,
                refund_status = EXCLUDED.refund_status,
                updated_at = EXCLUDED.updated_at
                """, orderId, sellerId, recordedAmountWon, status.name(), Timestamp.from(updatedAt));
    }

    private List<RefundItem> findItems(UUID refundId) {
        return jdbc.query("""
                SELECT order_item_id, quantity
                FROM order_refund_request_item
                WHERE refund_id = ?
                ORDER BY order_item_id
                """, resultSet -> {
            List<RefundItem> items = new ArrayList<>();
            while (resultSet.next()) {
                items.add(new RefundItem(resultSet.getObject("order_item_id", UUID.class),
                        resultSet.getInt("quantity")));
            }
            return List.copyOf(items);
        }, refundId);
    }
}
