package com.atlas.application.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.atlas.application.repository.DatabaseActivityRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * DB 전환 신호 게이지(ADR-0103, EXP-114)가 실제 PostgreSQL 통계에서 큐 표의 죽은 튜플과 advisory 락 대기를 읽고,
 * 대기가 끝나면 0으로 돌아가는지 확인한다.
 */
@Testcontainers
class DatabaseSignalMetricsIntegrationTest {
    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18.6")
            .withDatabaseName("atlas").withUsername("atlas").withPassword("atlas");

    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();

    @Test
    void queueTableDeadTuplesAndVacuumAreReported() throws Exception {
        try (SingleConnectionDataSource dataSource = dataSource()) {
            JdbcTemplate jdbc = new JdbcTemplate(dataSource);
            jdbc.execute("CREATE TABLE signal_queue (id int PRIMARY KEY, state text) WITH (autovacuum_enabled = false)");
            jdbc.execute("INSERT INTO signal_queue SELECT g, 'PENDING' FROM generate_series(1, 500) g");
            jdbc.execute("UPDATE signal_queue SET state = 'DONE'");
            jdbc.execute("SELECT pg_stat_force_next_flush()");
            DatabaseSignalMetrics metrics = metrics(jdbc, List.of("signal_queue", "absent_table"));

            metrics.refresh();

            assertThat(tableGauge("dead_tuples")).isEqualTo(500);
            assertThat(tableGauge("live_tuples")).isEqualTo(500);
            assertThat(tableGauge("total_bytes")).isPositive();
            assertThat(tableGauge("seconds_since_vacuum")).as("한 번도 VACUUM하지 않음").isEqualTo(-1);
            assertThat(meters.find("atlas.db.queue_table.dead_tuples").tag("table", "absent_table").gauge())
                    .as("없는 표는 게이지를 만들지 않는다").isNull();

            jdbc.execute("VACUUM signal_queue");
            jdbc.execute("SELECT pg_stat_force_next_flush()");
            metrics.refresh();

            assertThat(tableGauge("dead_tuples")).isZero();
            assertThat(tableGauge("seconds_since_vacuum")).isGreaterThanOrEqualTo(0);
            assertThat(meters.find("atlas.db.signals.refresh_failures").counter()).isNull();
        }
    }

    @Test
    void advisoryLockWaitIsReportedAndClearsAfterRelease() throws Exception {
        try (SingleConnectionDataSource dataSource = dataSource();
                Connection holder = connection();
                Connection waiter = connection()) {
            DatabaseSignalMetrics metrics = metrics(new JdbcTemplate(dataSource), List.of());
            holder.setAutoCommit(false);
            try (Statement statement = holder.createStatement()) {
                statement.execute("SELECT pg_advisory_xact_lock(hashtextextended('settlement-payout:signal', 0))");
            }
            CompletableFuture<Void> blocked = CompletableFuture.runAsync(() -> {
                try (Statement statement = waiter.createStatement()) {
                    statement.execute("SELECT pg_advisory_xact_lock(hashtextextended('settlement-payout:signal', 0))");
                } catch (SQLException exception) {
                    throw new IllegalStateException(exception);
                }
            });
            awaitWaitingSessions(dataSource);

            metrics.refresh();

            assertThat(lockGauge("sessions")).isEqualTo(1);
            assertThat(lockGauge("oldest_seconds")).isGreaterThanOrEqualTo(0);

            holder.commit();
            blocked.get(10, TimeUnit.SECONDS);
            metrics.refresh();

            assertThat(lockGauge("sessions")).as("대기가 끝나면 마지막 값이 남지 않는다").isZero();
            assertThat(lockGauge("oldest_seconds")).isZero();
        }
    }

    private DatabaseSignalMetrics metrics(JdbcTemplate jdbc, List<String> tables) {
        return new DatabaseSignalMetrics(new DatabaseActivityRepository(jdbc), meters, tables);
    }

    private double tableGauge(String name) {
        return meters.get("atlas.db.queue_table." + name).tag("table", "signal_queue").gauge().value();
    }

    private double lockGauge(String name) {
        return meters.get("atlas.db.lock_wait." + name).tag("wait_event", "advisory").gauge().value();
    }

    /** 대기 세션이 pg_stat_activity에 보일 때까지 조건을 기다린다(고정 sleep 대신). */
    private static void awaitWaitingSessions(SingleConnectionDataSource dataSource) throws InterruptedException {
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        Instant deadline = Instant.now().plus(Duration.ofSeconds(10));
        while (Instant.now().isBefore(deadline)) {
            Integer waiting = jdbc.queryForObject(
                    "SELECT count(*) FROM pg_stat_activity WHERE wait_event_type = 'Lock' AND wait_event = 'advisory'",
                    Integer.class);
            if (waiting != null && waiting == 1) return;
            Thread.onSpinWait();
        }
        throw new AssertionError("advisory lock waiter did not appear");
    }

    private static SingleConnectionDataSource dataSource() throws SQLException {
        return new SingleConnectionDataSource(connection(), true);
    }

    private static Connection connection() throws SQLException {
        return DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    }
}
