package com.atlas.application.persistence.projection;

/**
 * 지금 heavyweight 락을 기다리는 세션 묶음 하나({@code pg_stat_activity.wait_event_type = 'Lock'}).
 *
 * @param waitEvent {@code advisory}, {@code transactionid}, {@code tuple}, {@code relation} 등
 * @param waitingSessions 이 락 종류를 기다리는 세션 수
 * @param oldestWaitSeconds 가장 오래 기다린 세션의 현재 문장 경과 초(문장 시작부터라 대기 시간의 상한)
 */
public record LockWaitStats(String waitEvent, long waitingSessions, double oldestWaitSeconds) {
}
