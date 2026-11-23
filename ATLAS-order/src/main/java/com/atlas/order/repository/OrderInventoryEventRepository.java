package com.atlas.order.repository;

import com.atlas.order.service.OrderInventoryEvent;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * 예약 workflow 상태 변경 이벤트 로그를 소비자 그룹별 cursor로 읽는다. DB만 다룬다.
 *
 * <p>identity 값만으로 cursor를 만들면 늦게 커밋되는 낮은 시퀀스를 영구히 건너뛴다. 그래서 읽기는 항상
 * {@code tx_id < pg_snapshot_xmin(pg_current_snapshot())} 인 행으로 제한하고 ({@code tx_id}, {@code event_seq}) 순으로 반환한다.
 * horizon보다 아래에 있는 xid는 전부 커밋되었거나 중단되었으므로, 이후에 새 이벤트가 그 아래로 나타날 수 없다.
 */
@Repository
public class OrderInventoryEventRepository {
    private static final int MAX_GROUP_LENGTH = 64;
    private static final RowMapper<OrderInventoryEvent> EVENT_MAPPER = OrderInventoryEventRepository::mapEvent;
    private static final String CURSOR_SQL = """
            SELECT CAST(last_tx_id AS text) AS last_tx_id, last_event_seq
            FROM order_inventory_event_cursor
            WHERE consumer_group = ?
            """;

    private final JdbcTemplate jdbc;

    public OrderInventoryEventRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** cursor 뒤의 이벤트 중 horizon 아래에 있는 것만 최대 limit건 반환한다. cursor는 옮기지 않는다. */
    @Transactional(readOnly = true)
    public List<OrderInventoryEvent> readSafe(String consumerGroup, int limit) {
        validate(consumerGroup, limit);
        Cursor cursor = cursor(consumerGroup);
        return jdbc.query("""
                SELECT e.event_seq, CAST(e.tx_id AS text) AS tx_id, e.workflow_id, e.order_id, e.aggregate_version,
                       e.event_type, e.last_error_code, e.occurred_at
                FROM order_inventory_event e
                WHERE e.tx_id < pg_snapshot_xmin(pg_current_snapshot())
                  AND (e.tx_id, e.event_seq) > (CAST(CAST(? AS text) AS xid8), ?)
                ORDER BY e.tx_id, e.event_seq
                LIMIT ?
                """, EVENT_MAPPER, Long.toString(cursor.txId()), cursor.eventSeq(), limit);
    }

    /** last까지 소비했음을 기록한다. cursor는 뒤로 가지 않는다. */
    @Transactional
    public void advance(String consumerGroup, OrderInventoryEvent last) {
        validate(consumerGroup, 1);
        jdbc.update("""
                INSERT INTO order_inventory_event_cursor (consumer_group, last_tx_id, last_event_seq, updated_at)
                SELECT ?, e.tx_id, e.event_seq, clock_timestamp()
                FROM order_inventory_event e
                WHERE e.event_seq = ?
                ON CONFLICT (consumer_group) DO UPDATE
                SET last_tx_id = EXCLUDED.last_tx_id,
                    last_event_seq = EXCLUDED.last_event_seq,
                    updated_at = EXCLUDED.updated_at
                WHERE (order_inventory_event_cursor.last_tx_id, order_inventory_event_cursor.last_event_seq)
                    < (EXCLUDED.last_tx_id, EXCLUDED.last_event_seq)
                """, consumerGroup, last.eventSeq());
    }

    /**
     * relay lease를 얻는다. lease가 비었거나 만료된 경우에만 성공하고, 행이 없으면 처음부터 읽는 cursor 행을 만든다.
     * 짧은 트랜잭션 하나로 끝나며 전송 동안 아무 것도 잡고 있지 않는다.
     */
    @Transactional
    public boolean claimLease(String consumerGroup, UUID leaseToken, Duration leaseDuration) {
        validate(consumerGroup, 1);
        return jdbc.update("""
                INSERT INTO order_inventory_event_cursor (
                    consumer_group, last_tx_id, last_event_seq, lease_token, lease_until, updated_at
                ) VALUES (?, CAST('0' AS xid8), 0, ?, clock_timestamp() + make_interval(secs => ?),
                          clock_timestamp())
                ON CONFLICT (consumer_group) DO UPDATE
                SET lease_token = EXCLUDED.lease_token,
                    lease_until = EXCLUDED.lease_until
                WHERE order_inventory_event_cursor.lease_until IS NULL
                   OR order_inventory_event_cursor.lease_until < clock_timestamp()
                """, consumerGroup, leaseToken, leaseDuration.toMillis() / 1000.0) == 1;
    }

    /**
     * lease를 쥔 쪽만 last까지 cursor를 옮기고 lease를 반납한다. lease를 빼앗겼다면 0행이라 false를 반환하며,
     * 그 경우 호출자는 결과를 버려야 한다(다른 relay가 같은 이벤트를 이어서 보낸다).
     */
    @Transactional
    public boolean advanceFenced(String consumerGroup, OrderInventoryEvent last, UUID leaseToken) {
        validate(consumerGroup, 1);
        return jdbc.update("""
                UPDATE order_inventory_event_cursor c
                SET last_tx_id = e.tx_id,
                    last_event_seq = e.event_seq,
                    lease_token = NULL,
                    lease_until = NULL,
                    updated_at = clock_timestamp()
                FROM order_inventory_event e
                WHERE c.consumer_group = ?
                  AND c.lease_token = ?
                  AND e.event_seq = ?
                """, consumerGroup, leaseToken, last.eventSeq()) == 1;
    }

    /** 전송할 것이 없거나 전송이 실패했을 때 lease를 바로 반납해 다음 poll이 이어받게 한다. 토큰이 다르면 아무것도 하지 않는다. */
    @Transactional
    public void releaseLease(String consumerGroup, UUID leaseToken) {
        validate(consumerGroup, 1);
        jdbc.update("""
                UPDATE order_inventory_event_cursor
                SET lease_token = NULL, lease_until = NULL
                WHERE consumer_group = ? AND lease_token = ?
                """, consumerGroup, leaseToken);
    }

    /** cursor 뒤에 쌓인 이벤트 수(아직 horizon 위에 있어 못 읽는 것 포함). */
    @Transactional(readOnly = true)
    public long lagEvents(String consumerGroup) {
        validate(consumerGroup, 1);
        Cursor cursor = cursor(consumerGroup);
        Long lag = jdbc.queryForObject("""
                SELECT COUNT(*)
                FROM order_inventory_event e
                WHERE (e.tx_id, e.event_seq) > (CAST(CAST(? AS text) AS xid8), ?)
                """, Long.class, Long.toString(cursor.txId()), cursor.eventSeq());
        return lag == null ? 0L : lag;
    }

    /**
     * 이미 커밋됐지만 xmin horizon 위에 있어 소비자가 아직 못 읽는 가장 오래된 이벤트의 나이(초). 없으면 0.
     * 값이 계속 커지면 오래 열린 트랜잭션이 horizon을 붙잡고 있다는 뜻이다.
     */
    @Transactional(readOnly = true)
    public double stalledSeconds() {
        Double seconds = jdbc.queryForObject("""
                SELECT COALESCE(EXTRACT(EPOCH FROM (clock_timestamp() - MIN(e.occurred_at))), 0)::float8
                FROM order_inventory_event e
                WHERE e.tx_id >= pg_snapshot_xmin(pg_current_snapshot())
                """, Double.class);
        return seconds == null ? 0.0 : Math.max(0.0, seconds);
    }

    private Cursor cursor(String consumerGroup) {
        return jdbc.query(CURSOR_SQL,
                rs -> rs.next() ? new Cursor(Long.parseLong(rs.getString("last_tx_id")), rs.getLong("last_event_seq"))
                        : Cursor.START,
                consumerGroup);
    }

    private static void validate(String consumerGroup, int limit) {
        if (consumerGroup == null || consumerGroup.isBlank() || consumerGroup.length() > MAX_GROUP_LENGTH) {
            throw new IllegalArgumentException("Invalid event consumer group");
        }
        if (limit < 1 || limit > 10_000) throw new IllegalArgumentException("Invalid event read limit");
    }

    private static OrderInventoryEvent mapEvent(ResultSet rs, int rowNumber) throws SQLException {
        return new OrderInventoryEvent(rs.getLong("event_seq"), Long.parseLong(rs.getString("tx_id")),
                rs.getObject("workflow_id", UUID.class), rs.getObject("order_id", UUID.class),
                rs.getLong("aggregate_version"), rs.getString("event_type"), rs.getString("last_error_code"),
                rs.getTimestamp("occurred_at").toInstant());
    }

    private record Cursor(long txId, long eventSeq) {
        static final Cursor START = new Cursor(0L, 0L);
    }
}
