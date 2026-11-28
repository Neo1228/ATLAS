package com.atlas.order.repository;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;
import javax.sql.DataSource;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.UncategorizedSQLException;
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
 * <p>해결: 풀에서 연결 하나를 따로 받아 그 연결에 transaction을 열고 transaction 범위 advisory try-lock을
 * 잡은 채 sweep을 실행한 뒤 rollback으로 풉니다. 락을 못 잡은 replica는 이번 tick을 건너뜁니다(대기하지 않음).
 * sweep은 행마다 다른 연결의 별도 transaction으로 처리하고, 이 연결은 락만 쥡니다. 각 행의 최종 안전성은
 * 기존 상태 조건부 갱신과 PESSIMISTIC_WRITE가 계속 보장하므로, 이 락은 정확성이 아니라 중복 조회를 줄입니다.
 *
 * <p>session 락이 아니라 transaction 락인 이유(EXP-118 H5, ADR-0107): transaction pooling(PgBouncer
 * {@code pool_mode=transaction})은 transaction 동안만 서버 연결을 고정하므로 session-level advisory lock을
 * 지원하지 않습니다(https://www.pgbouncer.org/features.html, "Never"). transaction 락은 잡기부터 풀기까지 한
 * transaction이라 같은 서버 연결에 머뭅니다. 프로세스가 죽거나 연결이 끊기면 transaction이 끝나 락도 풀립니다.
 *
 * <p>락을 쥔 연결은 sweep 동안 idle in transaction으로 보입니다. sweep은 시간 예산(기본 2초)이 지나면 다음 행으로
 * 넘어가지 않으므로 보통 그 안에 끝나고, 늦어도 마지막 문장 하나의 {@code lock_timeout}(5초)이나
 * {@code statement_timeout}(30초)만큼 더 걸려 {@code idle_in_transaction_session_timeout}(60초)보다 짧습니다.
 * 쓰기를 하지 않아 xid를 받지 않으므로 마감 worker의 스냅숏 경계를 붙잡지 않습니다(EXP-118 S칸에서 xid 보유 0).
 */
@Repository
public class ExpirySweepLockRepository {
    private final JdbcTemplate jdbc;
    private final DataSource dataSource;

    public ExpirySweepLockRepository(JdbcTemplate jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc);
        this.dataSource = Objects.requireNonNull(jdbc.getDataSource());
    }

    /**
     * 같은 {@code workflow}의 sweep이 다른 곳에서 실행 중이 아닐 때만 {@code sweep}을 실행합니다.
     *
     * <p>락 연결은 호출자의 transaction에 묶인 연결이 아니라 풀에서 따로 받습니다. 묶인 연결을 쓰면 끝의
     * rollback이 호출자의 작업까지 되돌립니다.
     *
     * @return sweep 결과. 다른 replica가 실행 중이라 건너뛰었으면 비어 있습니다.
     */
    public <T> Optional<T> runExclusive(String workflow, Supplier<T> sweep) {
        Objects.requireNonNull(workflow);
        Objects.requireNonNull(sweep);
        String key = "expiry-sweep:" + workflow;
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                if (!tryLock(connection, key)) {
                    return Optional.empty();
                }
                return Optional.ofNullable(sweep.get());
            } finally {
                connection.rollback();
                connection.setAutoCommit(true);
            }
        } catch (SQLException exception) {
            DataAccessException translated = jdbc.getExceptionTranslator().translate("expiry sweep lock", null, exception);
            throw translated != null ? translated : new UncategorizedSQLException("expiry sweep lock", null, exception);
        }
    }

    private static boolean tryLock(Connection connection, String key) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT pg_try_advisory_xact_lock(hashtextextended(?, 0))")) {
            statement.setString(1, key);
            try (ResultSet result = statement.executeQuery()) {
                return result.next() && result.getBoolean(1);
            }
        }
    }
}
