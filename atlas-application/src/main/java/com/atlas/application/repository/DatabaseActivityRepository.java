package com.atlas.application.repository;

import com.atlas.application.persistence.projection.LockWaitStats;
import com.atlas.application.persistence.projection.QueueTableStats;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * EDA 전환 신호(EDA 검토 7절 T1·T6·T7, ADR-0103)를 PostgreSQL 통계 뷰에서 읽는다. 업무 표는 읽지 않는다.
 *
 * <p>두 질의 모두 공유 메모리의 통계·세션 정보만 읽어 원장·큐 크기와 무관하게 싸다(EXP-114에서 실행 시간 측정).
 */
@Repository
public class DatabaseActivityRepository {
    private final JdbcTemplate jdbc;

    public DatabaseActivityRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 현재 스키마 검색 경로에 있는 표 중 이름이 맞는 것만 돌려준다. 없는 표는 빠진다. */
    public List<QueueTableStats> queueTableStats(List<String> tables) {
        return jdbc.query("""
                SELECT relname, n_live_tup, n_dead_tup, pg_total_relation_size(relid) AS total_bytes,
                       COALESCE(EXTRACT(EPOCH FROM now() - GREATEST(last_autovacuum, last_vacuum))::bigint, -1)
                           AS seconds_since_vacuum
                FROM pg_stat_user_tables
                WHERE relname::text = ANY (?) AND schemaname = ANY (current_schemas(false))
                """, (rs, n) -> new QueueTableStats(rs.getString("relname"), rs.getLong("n_live_tup"),
                rs.getLong("n_dead_tup"), rs.getLong("total_bytes"), rs.getLong("seconds_since_vacuum")),
                (Object) tables.toArray(String[]::new));
    }

    /**
     * 같은 DB에서 heavyweight 락을 기다리는 세션을 락 종류별로 센다. advisory 락은 판매자 지급, SKU 재고, 구매자 등
     * 여러 용도가 같은 함수({@code pg_advisory_xact_lock(hashtextextended(...))})를 써서 DB 쪽에서는 용도를 가를 수 없다.
     */
    public List<LockWaitStats> lockWaits() {
        return jdbc.query("""
                SELECT wait_event, count(*) AS waiting,
                       COALESCE(MAX(EXTRACT(EPOCH FROM clock_timestamp() - query_start)), 0) AS oldest_seconds
                FROM pg_stat_activity
                WHERE wait_event_type = 'Lock' AND datname = current_database()
                GROUP BY wait_event
                """, (rs, n) -> new LockWaitStats(rs.getString("wait_event"), rs.getLong("waiting"),
                rs.getDouble("oldest_seconds")));
    }
}
