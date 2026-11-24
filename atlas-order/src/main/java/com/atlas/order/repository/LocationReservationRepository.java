package com.atlas.order.repository;

import com.atlas.order.model.LocationReservationState;
import com.atlas.order.model.LocationReservationWorkflowState;
import com.atlas.order.persistence.projection.ReservationWorkflowItemRecord;
import com.atlas.order.persistence.projection.ReservationWorkflowLease;
import com.atlas.order.persistence.projection.ReservationWorkflowLocationPlan;
import com.atlas.order.persistence.projection.ReservationWorkflowLocationSnapshot;
import com.atlas.order.persistence.projection.ReservationWorkflowSnapshot;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class LocationReservationRepository {
    private static final RowMapper<WorkflowHeader> WORKFLOW_MAPPER =
            LocationReservationRepository::mapWorkflow;
    private static final RowMapper<LocationHeader> LOCATION_MAPPER =
            LocationReservationRepository::mapLocation;

    private final JdbcTemplate jdbc;

    public LocationReservationRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional
    public ReservationWorkflowSnapshot save(UUID workflowId, UUID orderId, String idempotencyKey,
            List<ReservationWorkflowLocationPlan> plans, Instant now) {
        int inserted = jdbc.update("""
                INSERT INTO order_inventory_reservation_workflow (
                    workflow_id, order_id, idempotency_key, workflow_state, attempt_count,
                    next_attempt_at, created_at, updated_at
                ) VALUES (?, ?, ?, 'PENDING', 0, ?, ?, ?)
                ON CONFLICT (idempotency_key) DO NOTHING
                """, workflowId, orderId, idempotencyKey,
                Timestamp.from(now), Timestamp.from(now), Timestamp.from(now));
        if (inserted == 0) {
            WorkflowHeader existing = findHeaderByIdempotencyKeyForUpdate(idempotencyKey);
            return existing == null ? null : withLocations(existing);
        }
        for (ReservationWorkflowLocationPlan plan : plans) {
            String locationKey = locationIdempotencyKey(workflowId, plan.fulfillmentLocationId());
            jdbc.update("""
                    INSERT INTO order_inventory_location_command (
                        workflow_id, fulfillment_location_id, idempotency_key,
                        command_state, updated_at
                    ) VALUES (?, ?, ?, 'PENDING', ?)
                    """, workflowId, plan.fulfillmentLocationId(), locationKey, Timestamp.from(now));
            for (ReservationWorkflowItemRecord item : plan.items()) {
                jdbc.update("""
                        INSERT INTO order_inventory_location_item (
                            workflow_id, fulfillment_location_id, seller_id, sku_id, quantity
                        ) VALUES (?, ?, ?, ?, ?)
                        """, workflowId, plan.fulfillmentLocationId(), item.sellerId(), item.skuId(), item.quantity());
            }
        }
        WorkflowHeader created = findHeaderByWorkflowId(workflowId);
        if (created == null) throw new IllegalStateException("Inventory reservation workflow disappeared");
        return withLocations(created);
    }

    @Transactional(readOnly = true)
    public ReservationWorkflowSnapshot findByWorkflowId(UUID workflowId) {
        WorkflowHeader header = findHeaderByWorkflowId(workflowId);
        return header == null ? null : withLocations(header);
    }

    @Transactional(readOnly = true)
    public ReservationWorkflowSnapshot findByIdempotencyKey(String idempotencyKey) {
        WorkflowHeader header = findHeaderByIdempotencyKey(idempotencyKey);
        return header == null ? null : withLocations(header);
    }

    /**
     * 운영자가 봐야 하는 workflow 수. {@code claimedResidual}이 참이면 원격 재고가 CLAIMED로 남은 건만 센다.
     * RESERVED는 TTL로 스스로 풀리지만 CLAIMED는 사람이 풀기 전까지 판매 가능 수량으로 돌아오지 않는다.
     */
    @Transactional(readOnly = true)
    public long countNeedsReview(boolean claimedResidual) {
        String sql = claimedResidual
                ? """
                SELECT count(DISTINCT w.workflow_id) FROM order_inventory_reservation_workflow w
                JOIN order_inventory_location_command c ON c.workflow_id = w.workflow_id
                WHERE w.workflow_state = 'NEEDS_REVIEW' AND c.command_state = 'CLAIMED'
                """
                : "SELECT count(*) FROM order_inventory_reservation_workflow WHERE workflow_state = 'NEEDS_REVIEW'";
        Long count = jdbc.queryForObject(sql, Long.class);
        return count == null ? 0 : count;
    }

    @Transactional(readOnly = true)
    public double oldestDueAgeSeconds(Instant now) {
        Double ageSeconds = jdbc.queryForObject("""
                SELECT COALESCE(
                    EXTRACT(EPOCH FROM (CAST(? AS TIMESTAMPTZ) - MIN(next_attempt_at))),
                    0
                )::double precision
                FROM order_inventory_reservation_workflow
                WHERE workflow_state IN (
                    'PENDING', 'RETRY_PENDING', 'COMPENSATING', 'COMPENSATION_REQUIRED', 'CANCEL_PENDING'
                )
                  AND next_attempt_at <= ?
                """, Double.class, Timestamp.from(now), Timestamp.from(now));
        return ageSeconds == null ? 0 : Math.max(0, ageSeconds);
    }

    @Transactional
    public UUID claimWorkflow(UUID workflowId, Instant now, Instant leaseUntil) {
        UUID leaseToken = UUID.randomUUID();
        return claim(workflowId, leaseToken, now, leaseUntil) ? leaseToken : null;
    }

    @Transactional
    public UUID claimWorkflowForRetry(UUID workflowId, Instant now, Instant leaseUntil) {
        UUID leaseToken = UUID.randomUUID();
        return jdbc.update("""
                UPDATE order_inventory_reservation_workflow
                SET next_attempt_at = ?, lease_token = ?, lease_until = ?,
                    attempt_count = attempt_count + 1, updated_at = ?
                WHERE workflow_id = ?
                  AND workflow_state IN ('PENDING', 'RETRY_PENDING', 'COMPENSATING',
                                         'COMPENSATION_REQUIRED', 'CANCEL_PENDING')
                  AND (lease_token IS NULL OR lease_until <= ?)
                """, Timestamp.from(now), leaseToken, Timestamp.from(leaseUntil),
                Timestamp.from(now), workflowId, Timestamp.from(now)) == 1 ? leaseToken : null;
    }

    @Transactional
    public boolean requestCancellation(UUID workflowId, Instant now) {
        return jdbc.update("""
                UPDATE order_inventory_reservation_workflow
                SET workflow_state = 'CANCEL_PENDING', last_error_code = 'CANCEL_REQUESTED',
                    next_attempt_at = ?, lease_token = NULL, lease_until = NULL, updated_at = ?
                WHERE workflow_id = ?
                  AND workflow_state IN (
                      'PENDING', 'RETRY_PENDING', 'RESERVED', 'CLAIMED', 'COMPENSATING', 'COMPENSATION_REQUIRED'
                  )
                  -- 원격 claim 호출 중인 lease는 빼앗지 않는다. claim 결과가 기록된 뒤 취소가 claim을 되돌린다.
                  AND NOT (workflow_state IN ('RESERVED', 'CLAIMED') AND lease_until IS NOT NULL AND lease_until > ?)
                """, Timestamp.from(now), Timestamp.from(now), workflowId, Timestamp.from(now)) == 1;
    }

    /**
     * 운영자가 NEEDS_REVIEW를 보상 재실행으로 되돌린다. 시도 횟수를 0으로 되돌려 retry 한도를 새로 쓰고, 바로 due가 된다.
     * 이미 다른 상태면 아무것도 바꾸지 않으므로 반복 호출해도 안전하다.
     */
    @Transactional
    public boolean requeueNeedsReviewForCompensation(UUID workflowId, Instant now) {
        return jdbc.update("""
                UPDATE order_inventory_reservation_workflow
                SET workflow_state = 'COMPENSATION_REQUIRED', last_error_code = 'OPERATOR_RELEASE',
                    attempt_count = 0, next_attempt_at = ?, lease_token = NULL, lease_until = NULL, updated_at = ?
                WHERE workflow_id = ? AND workflow_state = 'NEEDS_REVIEW'
                """, Timestamp.from(now), Timestamp.from(now), workflowId) == 1;
    }

    @Transactional(readOnly = true)
    public List<UUID> findNeedsReviewIds(int limit) {
        return jdbc.queryForList("""
                SELECT workflow_id FROM order_inventory_reservation_workflow
                WHERE workflow_state = 'NEEDS_REVIEW' ORDER BY updated_at, workflow_id LIMIT ?
                """, UUID.class, limit);
    }

    /** 결제 승인 뒤 원격 재고 claim을 위해 RESERVED workflow에 단독 lease를 건다. */
    @Transactional
    public UUID leaseForClaim(UUID workflowId, Instant now, Instant leaseUntil) {
        UUID leaseToken = UUID.randomUUID();
        int updated = jdbc.update("""
                UPDATE order_inventory_reservation_workflow
                SET lease_token = ?, lease_until = ?, updated_at = ?
                WHERE workflow_id = ? AND workflow_state = 'RESERVED'
                  AND (lease_until IS NULL OR lease_until <= ?)
                """, leaseToken, Timestamp.from(leaseUntil), Timestamp.from(now), workflowId, Timestamp.from(now));
        return updated == 1 ? leaseToken : null;
    }

    /** 만료 전인 동일 lease token만 연장하며, 만료된 lease를 되살리지 않는다. */
    @Transactional
    public boolean renewLease(UUID workflowId, UUID leaseToken, Instant now, Instant leaseUntil) {
        return jdbc.update("""
                UPDATE order_inventory_reservation_workflow
                SET lease_until = ?
                WHERE workflow_id = ? AND lease_token = ? AND lease_until > ?
                """, Timestamp.from(leaseUntil), workflowId, leaseToken, Timestamp.from(now)) == 1;
    }

    /** 한 번에 한 workflow만 원자적으로 claim해 대기열의 lease 만료를 방지한다. */
    @Transactional
    public ReservationWorkflowLease claimNextDue(Instant dueThrough, Instant now, Instant leaseUntil) {
        UUID leaseToken = UUID.randomUUID();
        List<UUID> claims = jdbc.query("""
                WITH next_due AS (
                    SELECT workflow_id
                    FROM order_inventory_reservation_workflow
                    WHERE workflow_state IN (
                        'PENDING', 'RETRY_PENDING', 'COMPENSATING', 'COMPENSATION_REQUIRED', 'CANCEL_PENDING'
                    )
                      AND next_attempt_at <= ?
                      AND (lease_token IS NULL OR lease_until <= ?)
                    ORDER BY next_attempt_at, workflow_id
                    LIMIT 1 FOR UPDATE SKIP LOCKED
                )
                UPDATE order_inventory_reservation_workflow AS workflow
                SET lease_token = ?, lease_until = ?, attempt_count = attempt_count + 1, updated_at = ?
                FROM next_due
                WHERE workflow.workflow_id = next_due.workflow_id
                RETURNING workflow.workflow_id
                """, (resultSet, rowNum) -> resultSet.getObject("workflow_id", UUID.class),
                Timestamp.from(dueThrough), Timestamp.from(now), leaseToken,
                Timestamp.from(leaseUntil), Timestamp.from(now));
        return claims.isEmpty() ? null : new ReservationWorkflowLease(claims.getFirst(), leaseToken, leaseUntil);
    }

    @Transactional
    public boolean updateLocation(UUID workflowId, UUID leaseToken, UUID fulfillmentLocationId,
            LocationReservationState state, UUID reservationId, Instant remoteExpiresAt,
            String errorCode, Instant now) {
        return jdbc.update("""
                UPDATE order_inventory_location_command command
                SET command_state = ?, remote_reservation_id = COALESCE(?, remote_reservation_id),
                    remote_expires_at = COALESCE(?, remote_expires_at),
                    last_error_code = ?, updated_at = ?
                WHERE workflow_id = ? AND fulfillment_location_id = ?
                  AND EXISTS (
                      SELECT 1 FROM order_inventory_reservation_workflow workflow
                      WHERE workflow.workflow_id = command.workflow_id AND workflow.lease_token = ?
                  )
                """, state.name(), reservationId, remoteExpiresAt == null ? null : Timestamp.from(remoteExpiresAt),
                errorCode, Timestamp.from(now), workflowId, fulfillmentLocationId, leaseToken) == 1;
    }

    @Transactional
    public boolean updateWorkflowInFlight(UUID workflowId, UUID leaseToken,
            LocationReservationWorkflowState state, String errorCode, Instant nextAttemptAt, Instant now) {
        return jdbc.update("""
                UPDATE order_inventory_reservation_workflow
                SET workflow_state = ?, last_error_code = ?, next_attempt_at = ?, updated_at = ?
                WHERE workflow_id = ? AND lease_token = ?
                """, state.name(), errorCode, Timestamp.from(nextAttemptAt), Timestamp.from(now),
                workflowId, leaseToken) == 1;
    }

    @Transactional
    public boolean finishWorkflow(UUID workflowId, UUID leaseToken,
            LocationReservationWorkflowState state, String errorCode, Instant nextAttemptAt, Instant now) {
        return jdbc.update("""
                UPDATE order_inventory_reservation_workflow
                SET workflow_state = ?, last_error_code = ?, next_attempt_at = ?,
                    lease_token = NULL, lease_until = NULL, updated_at = ?
                WHERE workflow_id = ? AND lease_token = ?
                """, state.name(), errorCode, Timestamp.from(nextAttemptAt), Timestamp.from(now),
                workflowId, leaseToken) == 1;
    }

    private boolean claim(UUID workflowId, UUID leaseToken, Instant now, Instant leaseUntil) {
        return jdbc.update("""
                UPDATE order_inventory_reservation_workflow
                SET lease_token = ?, lease_until = ?, attempt_count = attempt_count + 1, updated_at = ?
                WHERE workflow_id = ?
                  AND workflow_state IN ('PENDING', 'RETRY_PENDING', 'COMPENSATING', 'COMPENSATION_REQUIRED', 'CANCEL_PENDING')
                  AND next_attempt_at <= ?
                  AND (lease_until IS NULL OR lease_until <= ?)
                """, leaseToken, Timestamp.from(leaseUntil), Timestamp.from(now), workflowId,
                Timestamp.from(now), Timestamp.from(now)) == 1;
    }

    private WorkflowHeader findHeaderByWorkflowId(UUID workflowId) {
        List<WorkflowHeader> rows = jdbc.query("""
                SELECT workflow_id, order_id, idempotency_key, workflow_state, attempt_count,
                       next_attempt_at, created_at, updated_at, last_error_code
                FROM order_inventory_reservation_workflow WHERE workflow_id = ?
                """, WORKFLOW_MAPPER, workflowId);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    private WorkflowHeader findHeaderByIdempotencyKey(String idempotencyKey) {
        List<WorkflowHeader> rows = jdbc.query("""
                SELECT workflow_id, order_id, idempotency_key, workflow_state, attempt_count,
                       next_attempt_at, created_at, updated_at, last_error_code
                FROM order_inventory_reservation_workflow WHERE idempotency_key = ?
                """, WORKFLOW_MAPPER, idempotencyKey);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    private WorkflowHeader findHeaderByIdempotencyKeyForUpdate(String idempotencyKey) {
        List<WorkflowHeader> rows = jdbc.query("""
                SELECT workflow_id, order_id, idempotency_key, workflow_state, attempt_count,
                       next_attempt_at, created_at, updated_at, last_error_code
                FROM order_inventory_reservation_workflow WHERE idempotency_key = ?
                FOR UPDATE
                """, WORKFLOW_MAPPER, idempotencyKey);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    private ReservationWorkflowSnapshot withLocations(WorkflowHeader header) {
        // (location, sku)당 한 row를 조회해 snapshot 조회마다 location별 추가 query(N+1)를 피한다.
        List<LocationItemRow> rows = jdbc.query("""
                SELECT command.fulfillment_location_id, command.idempotency_key, command.command_state,
                       command.remote_reservation_id, command.remote_expires_at, command.last_error_code,
                       command.updated_at, item.seller_id, item.sku_id, item.quantity
                FROM order_inventory_location_command command
                LEFT JOIN order_inventory_location_item item
                  ON item.workflow_id = command.workflow_id
                 AND item.fulfillment_location_id = command.fulfillment_location_id
                WHERE command.workflow_id = ?
                ORDER BY command.fulfillment_location_id, item.seller_id, item.sku_id
                """, (resultSet, rowNum) -> new LocationItemRow(
                resultSet.getObject("fulfillment_location_id", UUID.class),
                resultSet.getString("idempotency_key"), resultSet.getString("command_state"),
                resultSet.getObject("remote_reservation_id", UUID.class),
                resultSet.getTimestamp("remote_expires_at") == null
                        ? null : resultSet.getTimestamp("remote_expires_at").toInstant(),
                resultSet.getString("last_error_code"), resultSet.getTimestamp("updated_at").toInstant(),
                        resultSet.getObject("seller_id", UUID.class), resultSet.getString("sku_id"),
                resultSet.getObject("quantity", Integer.class)),
                header.workflowId());
        List<ReservationWorkflowLocationSnapshot> locations = new ArrayList<>();
        UUID currentLocation = null;
        List<ReservationWorkflowItemRecord> items = null;
        LocationItemRow currentLocationRow = null;
        for (LocationItemRow row : rows) {
            if (!row.locationId().equals(currentLocation)) {
                if (currentLocationRow != null) locations.add(toSnapshot(currentLocationRow, items));
                currentLocation = row.locationId();
                currentLocationRow = row;
                items = new ArrayList<>();
            }
            if (row.skuId() != null) items.add(new ReservationWorkflowItemRecord(row.sellerId(), row.skuId(), row.quantity()));
        }
        if (currentLocationRow != null) locations.add(toSnapshot(currentLocationRow, items));
        return new ReservationWorkflowSnapshot(header.workflowId(), header.orderId(), header.idempotencyKey(),
                LocationReservationWorkflowState.valueOf(header.state()), header.attemptCount(), header.nextAttemptAt(), header.createdAt(),
                header.updatedAt(), header.lastErrorCode(), locations);
    }

    private String locationIdempotencyKey(UUID workflowId, UUID locationId) {
        return workflowId + ":" + locationId;
    }

    private static ReservationWorkflowLocationSnapshot toSnapshot(LocationItemRow row, List<ReservationWorkflowItemRecord> items) {
        return new ReservationWorkflowLocationSnapshot(row.locationId(), row.idempotencyKey(), LocationReservationState.valueOf(row.state()), row.reservationId(),
                row.remoteExpiresAt(), row.lastErrorCode(), row.updatedAt(), List.copyOf(items));
    }

    private static WorkflowHeader mapWorkflow(ResultSet resultSet, int rowNum) throws SQLException {
        return new WorkflowHeader(resultSet.getObject("workflow_id", UUID.class),
                resultSet.getObject("order_id", UUID.class), resultSet.getString("idempotency_key"),
                resultSet.getString("workflow_state"), resultSet.getInt("attempt_count"),
                resultSet.getTimestamp("next_attempt_at").toInstant(),
                resultSet.getTimestamp("created_at").toInstant(),
                resultSet.getTimestamp("updated_at").toInstant(), resultSet.getString("last_error_code"));
    }

    private static LocationHeader mapLocation(ResultSet resultSet, int rowNum) throws SQLException {
        Timestamp expiry = resultSet.getTimestamp("remote_expires_at");
        return new LocationHeader(resultSet.getObject("fulfillment_location_id", UUID.class),
                resultSet.getString("idempotency_key"), resultSet.getString("command_state"),
                resultSet.getObject("remote_reservation_id", UUID.class),
                expiry == null ? null : expiry.toInstant(), resultSet.getString("last_error_code"),
                resultSet.getTimestamp("updated_at").toInstant());
    }

    private record LocationItemRow(UUID locationId, String idempotencyKey, String state, UUID reservationId,
                                   Instant remoteExpiresAt, String lastErrorCode, Instant updatedAt,
                                   UUID sellerId, String skuId, Integer quantity) {
    }

    private record WorkflowHeader(UUID workflowId, UUID orderId, String idempotencyKey, String state,
                                  int attemptCount, Instant nextAttemptAt, Instant createdAt,
                                  Instant updatedAt, String lastErrorCode) {
    }

    private record LocationHeader(UUID fulfillmentLocationId, String idempotencyKey, String state,
                                  UUID reservationId, Instant remoteExpiresAt, String lastErrorCode,
                                  Instant updatedAt) {
    }
}
