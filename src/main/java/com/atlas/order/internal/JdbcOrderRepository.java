package com.atlas.order.internal;

import com.atlas.order.OrderNotFoundException;
import com.atlas.order.OrderState;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.stereotype.Repository;

@Repository
class JdbcOrderRepository {

    private static final RowMapper<OrderRecord> ORDER_MAPPER = (rs, rowNum) -> new OrderRecord(
            rs.getObject("order_id", UUID.class), rs.getObject("campaign_id", UUID.class),
            rs.getObject("ticket_id", UUID.class), rs.getString("buyer_ref"), rs.getString("sku_id"),
            OrderState.valueOf(rs.getString("state")), rs.getTimestamp("checkout_expires_at").toInstant(),
            rs.getObject("payment_attempt_id", UUID.class));

    private static final String ORDER_COLUMNS = "order_id, campaign_id, ticket_id, buyer_ref, sku_id, state, checkout_expires_at, payment_attempt_id";
    private static final String UNRESOLVED_STATES = "'PAYMENT_SUBMITTED', 'PAYMENT_PENDING', 'PAYMENT_UNKNOWN', 'PAYMENT_VOID_PENDING', 'CANCEL_PENDING'";

    private final JdbcTemplate jdbc;

    JdbcOrderRepository(JdbcTemplate jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc);
    }

    void insert(OrderRecord order, Instant createdAt, Instant updatedAt) {
        jdbc.update("""
                INSERT INTO purchase_order
                    (order_id, campaign_id, ticket_id, buyer_ref, sku_id, state,
                     checkout_expires_at, payment_attempt_id, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, NULL, ?, ?)
                """, order.orderId(), order.campaignId(), order.ticketId(), order.buyerRef(), order.skuId(),
                order.state().name(), Timestamp.from(order.checkoutExpiresAt()),
                Timestamp.from(createdAt), Timestamp.from(updatedAt));
    }

    OrderRecord findById(UUID orderId) {
        return jdbc.query("SELECT " + ORDER_COLUMNS + " FROM purchase_order WHERE order_id = ?",
                ORDER_MAPPER, orderId).stream().findFirst().orElseThrow(() -> new OrderNotFoundException(orderId));
    }

    OrderRecord lockById(UUID orderId) {
        return jdbc.query("SELECT " + ORDER_COLUMNS + " FROM purchase_order WHERE order_id = ? FOR UPDATE",
                ORDER_MAPPER, orderId).stream().findFirst().orElseThrow(() -> new OrderNotFoundException(orderId));
    }

    OrderRecord findByTicketId(UUID ticketId) {
        return jdbc.query("SELECT " + ORDER_COLUMNS + " FROM purchase_order WHERE ticket_id = ?",
                ORDER_MAPPER, ticketId).stream().findFirst().orElse(null);
    }

    List<UUID> findReservedOrdersExpiredBy(Instant now) {
        return jdbc.query("""
                SELECT order_id FROM purchase_order
                WHERE state = 'RESERVED' AND checkout_expires_at <= ?
                ORDER BY checkout_expires_at, order_id
                """, (rs, rowNum) -> rs.getObject("order_id", UUID.class), Timestamp.from(now));
    }

    void updateState(UUID orderId, OrderState state, UUID paymentAttemptId, Instant updatedAt) {
        Timestamp timestamp = Timestamp.from(updatedAt);
        if (paymentAttemptId == null) {
            jdbc.update("UPDATE purchase_order SET state = ?, updated_at = ? WHERE order_id = ?",
                    state.name(), timestamp, orderId);
        } else {
            jdbc.update("UPDATE purchase_order SET state = ?, payment_attempt_id = ?, updated_at = ? WHERE order_id = ?",
                    state.name(), paymentAttemptId, timestamp, orderId);
        }
    }

    void lockBuyer(OrderRecord order) {
        jdbc.query("SELECT pg_advisory_xact_lock(hashtextextended(?, 0))",
                (RowCallbackHandler) rs -> { },
                order.campaignId() + ":" + order.buyerRef());
    }

    boolean hasOtherUnresolvedPayment(OrderRecord order) {
        Boolean exists = jdbc.queryForObject("SELECT EXISTS (SELECT 1 FROM purchase_order "
                        + "WHERE campaign_id = ? AND buyer_ref = ? AND order_id <> ? AND state IN ("
                        + UNRESOLVED_STATES + "))",
                Boolean.class, order.campaignId(), order.buyerRef(), order.orderId());
        return Boolean.TRUE.equals(exists);
    }
}
