package com.atlas;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.util.concurrent.TimeUnit;

/** 지정한 트랜잭션에 의해 대기 중인 PostgreSQL backend를 관찰하는 테스트 전용 도구. */
public final class PostgresLockWaitProbe {
    private static final String WAITING_BACKEND_SQL = """
            SELECT pid, wait_event,
                   EXTRACT(EPOCH FROM (clock_timestamp() - query_start)) * 1000 AS query_elapsed_ms
            FROM pg_stat_activity
            WHERE pid <> ?
              AND wait_event_type = 'Lock'
              AND ? = ANY(pg_blocking_pids(pid))
            """;

    private PostgresLockWaitProbe() {
    }

    public static int backendPid(Connection connection) throws SQLException {
        try (var statement = connection.createStatement();
             ResultSet rows = statement.executeQuery("SELECT pg_backend_pid()")) {
            if (!rows.next()) {
                throw new SQLException("PostgreSQL did not return the backend PID");
            }
            return rows.getInt(1);
        }
    }

    public static Observation awaitWaiter(
            Connection observer,
            int blockerPid,
            int observerPid,
            Duration timeout,
            Duration pollInterval) throws SQLException, InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        String lastObservation = "no matching backend observed";
        while (System.nanoTime() < deadline) {
            try (PreparedStatement query = observer.prepareStatement(WAITING_BACKEND_SQL)) {
                query.setInt(1, observerPid);
                query.setInt(2, blockerPid);
                try (ResultSet rows = query.executeQuery()) {
                    if (rows.next()) {
                        Observation observation = new Observation(
                                rows.getInt("pid"), rows.getString("wait_event"),
                                rows.getDouble("query_elapsed_ms"));
                        if (rows.next()) {
                            throw new AssertionError("Expected exactly one matching PostgreSQL lock waiter");
                        }
                        return observation;
                    }
                }
            }
            lastObservation = "no lock waiter matched blocker backend " + blockerPid;
            long remaining = deadline - System.nanoTime();
            if (remaining > 0) {
                TimeUnit.NANOSECONDS.sleep(Math.min(pollInterval.toNanos(), remaining));
            }
        }
        throw new AssertionError("Timed out waiting for PostgreSQL lock waiter: " + lastObservation);
    }

    /** queryElapsedMillis는 표본 시점의 query 경과 시간이며 운영 lock-wait percentile이 아님. */
    public record Observation(int waiterPid, String waitEvent, double queryElapsedMillis) {
    }
}
