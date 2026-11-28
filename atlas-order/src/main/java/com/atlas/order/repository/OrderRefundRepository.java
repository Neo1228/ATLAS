package com.atlas.order.repository;

import com.atlas.order.persistence.projection.RefundHeader;
import com.atlas.order.persistence.projection.RefundItem;
import com.atlas.order.persistence.projection.RefundRecord;
import com.atlas.order.persistence.projection.RefundSummary;
import com.atlas.order.model.OrderRefundStatus;
import com.atlas.order.model.OrderRefundPaymentStatus;
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

    /**
     * 한 주문 안의 환불 idempotency key 를 transaction 끝까지 직렬화한다.
     *
     * <p>key 의 유일 범위는 (order_id, idempotency_key) 다. 잠금 키에도 주문 id 를 넣어야
     * 다른 주문이 같은 key 를 쓸 때 서로 기다리지 않는다.
     */
    public void lockIdempotencyKey(UUID orderId, String idempotencyKey) {
        jdbc.query("SELECT pg_advisory_xact_lock(hashtextextended(?, 0))",
                (RowCallbackHandler) rs -> { }, "order-refund:" + orderId + ":" + idempotencyKey);
    }

    /**
     * 특정 접두사로 시작하는 환불 요청 수를 센다. 반품 환불의 재시도 회차를 정하는 데 쓴다.
     *
     * <p>반품 환불 키는 호출자가 고르지 못한다. 첫 시도는 {@code "return-<returnId>"},
     * 재시도는 {@code "return-<returnId>-r1"}, {@code "-r2"} 처럼 회차를 붙인다.
     *
     * <p>주의: 접두사 매칭이므로 반드시 회차 구분자까지 포함해 넘겨야 한다. 단순히
     * {@code "return-<returnId>"} 로 LIKE 하면 다른 접미사가 붙은 키까지 걸린다.
     *
     * <p>key 의 유일 범위가 주문이므로 서비스는 주문을 지정한 {@link #countByKeyPrefix(UUID, String)}
     * 를 쓴다. 이 전역 버전은 반품 id 가 들어간 접두사처럼 이미 전역에서 구분되는 값에만 쓴다.
     */
    public int countByKeyPrefix(String prefix) {
        String escaped = prefix
                .replace("\\", "\\\\")
                .replace("%", "\\%")
                .replace("_", "\\_");
        Integer count = jdbc.queryForObject(
                "SELECT count(*) FROM order_refund_request WHERE idempotency_key LIKE ? ESCAPE '\\'",
                Integer.class, escaped + "%");
        return count == null ? 0 : count;
    }

    /** 한 주문 안에서 특정 접두사로 시작하는 환불 요청 수를 센다. 접두사 규칙은 위와 같다. */
    public int countByKeyPrefix(UUID orderId, String prefix) {
        String escaped = prefix
                .replace("\\", "\\\\")
                .replace("%", "\\%")
                .replace("_", "\\_");
        Integer count = jdbc.queryForObject(
                "SELECT count(*) FROM order_refund_request WHERE order_id = ? AND idempotency_key LIKE ? ESCAPE '\\'",
                Integer.class, orderId, escaped + "%");
        return count == null ? 0 : count;
    }

    public Optional<RefundRecord> findByIdempotencyKey(UUID orderId, String idempotencyKey) {
        return findRecord("order_id = ? AND idempotency_key = ?", orderId, idempotencyKey);
    }

    public Optional<RefundRecord> findByRefundId(UUID refundId) {
        return findRecord("refund_id = ?", refundId);
    }

    /**
     * payment 계층에 넘길 환불 key 를 돌려준다. 직접 넣은 행처럼 컬럼이 비어 있으므로
     * 원래 idempotency key 를 쓴다(이미 그 key 로 payment 작업이 만들어졌다).
     */
    public String paymentIdempotencyKey(UUID refundId) {
        return jdbc.queryForObject("""
                SELECT COALESCE(payment_idempotency_key, idempotency_key)
                FROM order_refund_request
                WHERE refund_id = ?
                """, String.class, refundId);
    }

    private Optional<RefundRecord> findRecord(String condition, Object... args) {
        List<RefundHeader> rows = jdbc.query("""
                SELECT refund_id, order_id, seller_id, idempotency_key, amount_won, refund_status, created_at
                FROM order_refund_request
                WHERE
                """ + condition, (resultSet, rowNum) -> new RefundHeader(
                resultSet.getObject("refund_id", UUID.class), resultSet.getObject("order_id", UUID.class),
                resultSet.getObject("seller_id", UUID.class), resultSet.getString("idempotency_key"),
                resultSet.getLong("amount_won"), OrderRefundStatus.valueOf(resultSet.getString("refund_status")),
                resultSet.getTimestamp("created_at").toInstant()), args);
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

    /**
     * 운영자 환불을 {@code domain_audit_event}에 한 번 남긴다. 같은 환불의 재시도는 행을 더 만들지 않는다
     * (같은 key는 {@link #lockIdempotencyKey}로 직렬화된 같은 트랜잭션에서 부른다).
     *
     * <p>다른 운영자 행위는 원천 표의 INSERT trigger가 이 표를 채운다(V9). 환불 요청 행은 Seller 환불과
     * 같은 표라 trigger를 걸면 Seller 환불까지 운영자 행위로 기록되므로, 운영자 경로에서만 직접 쓴다.
     * payload에는 자유 텍스트와 idempotency key를 넣지 않는다(V9 규약).
     */
    public void recordOperatorRefundAudit(UUID refundId, UUID orderId, UUID sellerId, long amountWon, String actor,
            Instant occurredAt) {
        jdbc.update("""
                INSERT INTO domain_audit_event(occurred_at, source_table, source_id, event_type, aggregate_type,
                                               aggregate_id, actor, payload)
                SELECT ?, 'order_refund_request', ?, 'ORDER_REFUND_BY_OPERATOR', 'order', ?, ?,
                       jsonb_build_object('refund_id', ?::text, 'order_id', ?::text, 'seller_id', ?::text,
                                          'amount_won', ?::bigint)
                WHERE NOT EXISTS (
                    SELECT 1 FROM domain_audit_event
                    WHERE aggregate_type = 'order' AND aggregate_id = ?
                      AND source_table = 'order_refund_request' AND source_id = ?
                      AND event_type = 'ORDER_REFUND_BY_OPERATOR')
                """, Timestamp.from(occurredAt), refundId.toString(), orderId.toString(), actor,
                refundId.toString(), orderId.toString(), sellerId.toString(), amountWon,
                orderId.toString(), refundId.toString());
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
        List<UUID> refundIds = jdbc.query("""
                SELECT refund_id FROM order_refund_request
                WHERE payment_refund_operation_id = ?
                FOR UPDATE
                """, (resultSet, rowNum) -> resultSet.getObject("refund_id", UUID.class), paymentOperationId);
        return refundIds.stream().findFirst().flatMap(this::findByRefundId);
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

    public UUID insertRequest(UUID orderId, UUID sellerId, String idempotencyKey, String paymentIdempotencyKey,
                              long recordedAmountWon, OrderRefundStatus status, Instant createdAt) {
        return jdbc.queryForObject("""
                INSERT INTO order_refund_request
                (order_id, seller_id, idempotency_key, payment_idempotency_key, amount_won, refund_status,
                 payment_refund_status, created_at)
                VALUES (?, ?, ?, ?, ?, ?, 'PENDING', ?)
                RETURNING refund_id
                """, UUID.class, orderId, sellerId, idempotencyKey, paymentIdempotencyKey, recordedAmountWon,
                status.name(), Timestamp.from(createdAt));
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
