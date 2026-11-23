package com.atlas.order.repository;

import com.atlas.order.persistence.projection.LocationRestock;
import com.atlas.order.persistence.projection.WorkflowItemRecord;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/** 원격 재고 환불 재입고 의도와 재시도 상태를 저장한다. 원격 호출 여부와 무관하게 DB만 다룬다. */
@Repository
public class LocationRestockRepository {
    private final JdbcTemplate jdbc;

    public LocationRestockRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 호출자의 transaction에 참여한다. 같은 key·location 재기록은 무시한다. */
    @Transactional
    public void insert(String restockKey, UUID locationId, UUID refundId, UUID orderId, UUID workflowId,
                       UUID reservationId, List<WorkflowItemRecord> items, Instant now) {
        int inserted = jdbc.update("""
                INSERT INTO order_inventory_restock
                    (restock_key, fulfillment_location_id, refund_id, order_id, workflow_id, remote_reservation_id,
                     restock_state, attempt_count, next_attempt_at, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, 'PENDING', 0, ?, ?, ?)
                ON CONFLICT DO NOTHING
                """, restockKey, locationId, refundId, orderId, workflowId, reservationId,
                Timestamp.from(now), Timestamp.from(now), Timestamp.from(now));
        if (inserted == 0) return;
        for (WorkflowItemRecord item : items) {
            jdbc.update("""
                    INSERT INTO order_inventory_restock_item
                        (restock_key, fulfillment_location_id, seller_id, sku_id, quantity)
                    VALUES (?, ?, ?, ?, ?)
                    """, restockKey, locationId, item.sellerId(), item.skuId(), item.quantity());
        }
    }

    /** 처리할 PENDING 의도를 고르고 next_attempt_at을 lease 만료 시각으로 미뤄 다른 worker의 중복 선택을 줄인다. */
    @Transactional
    public List<LocationRestock> claimDue(Instant now, Instant leaseUntil, int limit, String onlyRestockKey) {
        List<LocationRestock> due = jdbc.query("""
                SELECT restock_key, fulfillment_location_id, refund_id, order_id, workflow_id,
                       remote_reservation_id, attempt_count
                FROM order_inventory_restock
                WHERE restock_state = 'PENDING' AND next_attempt_at <= ?
                  AND (CAST(? AS VARCHAR) IS NULL OR restock_key = ?)
                ORDER BY next_attempt_at, restock_key
                LIMIT ? FOR UPDATE SKIP LOCKED
                """, (rs, row) -> new LocationRestock(rs.getString("restock_key"),
                rs.getObject("fulfillment_location_id", UUID.class), rs.getObject("refund_id", UUID.class),
                rs.getObject("order_id", UUID.class), rs.getObject("workflow_id", UUID.class),
                rs.getObject("remote_reservation_id", UUID.class), rs.getInt("attempt_count"), List.of()),
                Timestamp.from(now), onlyRestockKey, onlyRestockKey, limit);
        return due.stream().map(restock -> {
            jdbc.update("""
                    UPDATE order_inventory_restock SET next_attempt_at = ?, attempt_count = attempt_count + 1,
                        updated_at = ?
                    WHERE restock_key = ? AND fulfillment_location_id = ?
                    """, Timestamp.from(leaseUntil), Timestamp.from(now), restock.restockKey(),
                    restock.fulfillmentLocationId());
            return new LocationRestock(restock.restockKey(), restock.fulfillmentLocationId(), restock.refundId(),
                    restock.orderId(), restock.workflowId(), restock.reservationId(), restock.attemptCount() + 1,
                    items(restock.restockKey(), restock.fulfillmentLocationId()));
        }).toList();
    }

    /**
     * claim한 worker만 마감할 수 있다. claim마다 attempt_count가 올라가므로, lease가 만료된 뒤 늦게 깨어난
     * worker는 낡은 횟수를 들고 있어 갱신이 0행이 되고 더 새로운 claim의 결과를 덮어쓰지 못한다.
     */
    @Transactional
public void finish(String restockKey, UUID locationId, int attemptCount, String state, String errorCode, Instant nextAttemptAt,
                       Instant now) {
        jdbc.update("""
                UPDATE order_inventory_restock
                SET restock_state = ?, last_error_code = ?, next_attempt_at = ?, updated_at = ?
                WHERE restock_key = ? AND fulfillment_location_id = ? AND restock_state = 'PENDING' AND attempt_count = ?
                """, state, errorCode, Timestamp.from(nextAttemptAt), Timestamp.from(now), restockKey, locationId, attemptCount);
    }

    @Transactional(readOnly = true)
    public List<String> states(String restockKey) {
        return jdbc.queryForList("SELECT restock_state FROM order_inventory_restock WHERE restock_key = ?",
                String.class, restockKey);
    }

    @Transactional(readOnly = true)
    public double oldestDueAgeSeconds(Instant now) {
        Double ageSeconds = jdbc.queryForObject("""
                SELECT COALESCE(
                    EXTRACT(EPOCH FROM (CAST(? AS TIMESTAMPTZ) - MIN(next_attempt_at))),
                    0
                )::double precision
                FROM order_inventory_restock
                WHERE restock_state = 'PENDING' AND next_attempt_at <= ?
                """, Double.class, Timestamp.from(now), Timestamp.from(now));
        return ageSeconds == null ? 0 : Math.max(0, ageSeconds);
    }

    private List<WorkflowItemRecord> items(String restockKey, UUID locationId) {
        return jdbc.query("""
                SELECT seller_id, sku_id, quantity FROM order_inventory_restock_item
                WHERE restock_key = ? AND fulfillment_location_id = ? ORDER BY seller_id, sku_id
                """, (rs, row) -> new WorkflowItemRecord(rs.getObject("seller_id", UUID.class), rs.getString("sku_id"),
                rs.getInt("quantity")), restockKey, locationId);
    }
}
