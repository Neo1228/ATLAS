package com.atlas.application.persistence.projection;

/**
 * 큐 표 하나의 부풀음 지표(PostgreSQL 누적 통계). 값은 통계 수집기의 추정치라 정확한 행 수가 아니다.
 *
 * @param table 표 이름
 * @param liveTuples {@code pg_stat_user_tables.n_live_tup}
 * @param deadTuples {@code pg_stat_user_tables.n_dead_tup}
 * @param totalBytes {@code pg_total_relation_size}(힙, 인덱스, TOAST)
 * @param secondsSinceVacuum 마지막 VACUUM(자동·수동 중 늦은 것) 뒤 지난 초. 한 번도 없으면 -1
 */
public record QueueTableStats(String table, long liveTuples, long deadTuples, long totalBytes, long secondsSinceVacuum) {
}
