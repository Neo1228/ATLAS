package com.atlas.order.repository;

import java.util.Objects;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * 만료 sweep 후보 조회를 replica 간에 직렬화합니다.
 *
 * <p>문제: {@code expireDueOrders}와 {@code expireDueCheckouts}는 claim 단계 없이 모든
 * replica가 같은 상위 N행을 매 초 조회합니다. 효과는 상태 조건부 갱신으로 한 번만
 * 일어나지만, replica N개면 후보 조회 N회와 행마다 PESSIMISTIC_WRITE 경쟁이 발생합니다.
 * EXP-037에서 replica 3개일 때 sweep 실행이 2분간 358회(초당 약 3회)로 관측됐습니다.
 *
 * <p>해결: 트랜잭션 범위 advisory try-lock으로 sweep 한 번을 한 replica만 수행하게
 * 합니다. 락을 못 잡은 replica는 이번 tick을 건너뜁니다(대기하지 않음). 후보 조회와
 * 개별 row 처리 사이에 커밋이 끼므로 락은 "후보 조회 + 처리 시작" 구간만 보호합니다.
 * 이는 중복 조회를 없애는 것으로 충분하며, 각 행의 최종 안전성은 기존 상태 조건부
 * 갱신과 PESSIMISTIC_WRITE가 계속 보장합니다.
 */
@Repository
public class JdbcExpirySweepLockRepository {
    private final JdbcTemplate jdbc;

    public JdbcExpirySweepLockRepository(JdbcTemplate jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc);
    }

    /**
     * @return true면 이 replica가 이번 sweep을 수행합니다. false면 다른 replica가
     *     수행 중이므로 이번 tick을 건너뜁니다.
     */
    public boolean tryAcquire(String workflow) {
        Boolean acquired = jdbc.queryForObject(
                "SELECT pg_try_advisory_xact_lock(hashtextextended(?, 0))",
                Boolean.class, "expiry-sweep:" + workflow);
        return Boolean.TRUE.equals(acquired);
    }
}
