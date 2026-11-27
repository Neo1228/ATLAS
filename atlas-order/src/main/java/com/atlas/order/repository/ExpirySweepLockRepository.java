package com.atlas.order.repository;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * 만료 sweep을 replica 간에 한 번에 하나만 실행하도록 직렬화합니다.
 *
 * <p>문제: {@code expireDueOrders}와 {@code expireDueCheckouts}는 claim 단계 없이 모든 replica가 같은
 * 상위 N행을 매 초 조회합니다. 효과는 상태 조건부 갱신으로 한 번만 일어나지만, replica N개면 후보 조회 N회와
 * 행마다 PESSIMISTIC_WRITE 경쟁이 발생합니다. EXP-037에서 replica 3개일 때 sweep 실행이 2분간 358회(초당 약
 * 3회)로 관측됐습니다.
 *
 * <p>해결: 연결 하나를 sweep 동안 점유하고 그 연결에 session advisory try-lock을 잡습니다. 락을 못 잡은
 * replica는 이번 tick을 건너뜁니다(대기하지 않음). sweep은 행마다 별도 transaction으로 처리하므로 transaction
 * 범위 락({@code pg_try_advisory_xact_lock})은 쓸 수 없습니다. 그 락은 transaction 밖에서는 문장이 끝나는
 * 즉시 풀려 아무것도 직렬화하지 못합니다. 각 행의 최종 안전성은 기존 상태 조건부 갱신과
 * PESSIMISTIC_WRITE가 계속 보장합니다.
 *
 * <p>연결이 끊기면 서버가 session 락을 함께 해제하므로, 프로세스가 죽어도 락이 남지 않습니다.
 *
 * <p>배포 전제: session 락이므로 앱과 PostgreSQL 사이에 transaction pooling(PgBouncer {@code pool_mode=transaction})을
 * 두면 안 됩니다. PgBouncer 기능 표는 transaction pooling에서 session-level advisory lock을 "Never"로 적습니다
 * (https://www.pgbouncer.org/features.html). 잡기와 풀기가 다른 서버 연결로 가면 락이 그 서버 연결에 남아 모든
 * replica가 sweep을 건너뛰고(만료가 멈춤), 같은 서버 연결로 다시 가면 재진입으로 둘이 동시에 실행합니다. pooler를
 * 넣을 때는 session pooling을 쓰거나 이 락을 lease 행으로 바꿉니다(EXP-118, ADR-0107).
 */
@Repository
public class ExpirySweepLockRepository {
    private final JdbcTemplate jdbc;

    public ExpirySweepLockRepository(JdbcTemplate jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc);
    }

    /**
     * 같은 {@code workflow}의 sweep이 다른 곳에서 실행 중이 아닐 때만 {@code sweep}을 실행합니다.
     *
     * @return sweep 결과. 다른 replica가 실행 중이라 건너뛰었으면 비어 있습니다.
     */
    public <T> Optional<T> runExclusive(String workflow, Supplier<T> sweep) {
        Objects.requireNonNull(workflow);
        Objects.requireNonNull(sweep);
        String key = "expiry-sweep:" + workflow;
        return jdbc.execute((ConnectionCallback<Optional<T>>) connection -> {
            if (!tryLock(connection, key)) {
                return Optional.empty();
            }
            try {
                return Optional.ofNullable(sweep.get());
            } finally {
                unlock(connection, key);
            }
        });
    }

    private static boolean tryLock(Connection connection, String key) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT pg_try_advisory_lock(hashtextextended(?, 0))")) {
            statement.setString(1, key);
            try (ResultSet result = statement.executeQuery()) {
                return result.next() && result.getBoolean(1);
            }
        }
    }

    private static void unlock(Connection connection, String key) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT pg_advisory_unlock(hashtextextended(?, 0))")) {
            statement.setString(1, key);
            statement.execute();
        }
    }
}
