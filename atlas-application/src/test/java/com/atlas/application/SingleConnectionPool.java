package com.atlas.application;

import com.zaxxer.hikari.HikariDataSource;
import com.zaxxer.hikari.HikariPoolMXBean;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;

/**
 * 기동이 끝난 뒤 연결 풀을 1개로 줄이는 통합 테스트용 도우미다.
 *
 * <p>시작 단계(Flyway, Hibernate)는 연결이 둘 이상 필요해서 풀을 처음부터 1개로 만들 수 없다. 기동한 뒤
 * 줄이면 이전 연결이 사용 중이거나 정리 중일 수 있고, 풀이 비어 있으면 요청이 올 때까지 새 연결을 만들지
 * 않는다. 그 상태에서 요청하면 테스트가 검증하려는 동작이 아니라 풀 정리 타이밍에 따라 결과가 갈린다. 줄인
 * 뒤 연결을 하나 빌렸다 돌려주어 풀이 실제로 연결 1개만 가진 상태가 될 때까지 기다린다.
 */
public final class SingleConnectionPool {
    private static final long SETTLE_TIMEOUT_SECONDS = 15;

    private SingleConnectionPool() {
    }

    /** 풀을 연결 1개로 줄이고, 그 연결 하나만 남아 유휴 상태가 될 때까지 기다린다. */
    public static HikariDataSource shrink(DataSource dataSource) throws Exception {
        HikariDataSource pool = dataSource.unwrap(HikariDataSource.class);
        pool.setMinimumIdle(1);
        pool.setMaximumPoolSize(1);
        HikariPoolMXBean metrics = pool.getHikariPoolMXBean();
        metrics.softEvictConnections();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(SETTLE_TIMEOUT_SECONDS);
        while (System.nanoTime() < deadline) {
            try (Connection connection = pool.getConnection()) {
                if (connection.isValid(2) && metrics.getTotalConnections() == 1) {
                    return pool;
                }
            } catch (SQLException unavailable) {
                // 정리 중인 연결과 겹친 것이므로 잠시 뒤 다시 시도한다.
            }
            Thread.sleep(50);
        }
        throw new IllegalStateException("연결 풀이 " + SETTLE_TIMEOUT_SECONDS + "초 안에 연결 1개로 정리되지 않았다");
    }
}
