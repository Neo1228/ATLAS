package com.atlas.order;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.postgresql.PostgreSQLContainer;

/** 이벤트 로그 테스트가 공유하는 스키마 준비와 workflow 입력 helper. */
final class ReservationEventTestSupport {
    private static final String INSERT_WORKFLOW = """
            INSERT INTO order_inventory_reservation_workflow (
                workflow_id, order_id, idempotency_key, workflow_state, attempt_count,
                next_attempt_at, created_at, updated_at
            ) VALUES (?, ?, ?, 'PENDING', 0, now(), now(), now())
            """;

    private ReservationEventTestSupport() {
    }

    /** 테스트마다 새 스키마를 만들고 모든 migration을 적용한다. 연결은 호출마다 새로 열린다. */
    static DriverManagerDataSource migratedDataSource(PostgreSQLContainer postgres) {
        String schema = "event_log_test_" + UUID.randomUUID().toString().replace("-", "");
        new JdbcTemplate(new DriverManagerDataSource(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword()))
                .execute("CREATE SCHEMA " + schema);
        String url = postgres.getJdbcUrl() + (postgres.getJdbcUrl().contains("?") ? "&" : "?")
                + "currentSchema=" + schema;
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                url, postgres.getUsername(), postgres.getPassword());
        Flyway.configure()
                .dataSource(dataSource)
                .schemas(schema)
                .defaultSchema(schema)
                .locations("classpath:db/migration")
                .load()
                .migrate();
        return dataSource;
    }

    static UUID insertWorkflow(JdbcTemplate jdbc) {
        UUID workflowId = UUID.randomUUID();
        jdbc.update(INSERT_WORKFLOW, workflowId, UUID.randomUUID(), "event-log-" + workflowId);
        return workflowId;
    }

    /** 커밋은 호출자가 한다. 열린 트랜잭션이 이벤트와 xid를 들고 있게 만들 때 쓴다. */
    static UUID insertWorkflow(Connection connection) throws SQLException {
        UUID workflowId = UUID.randomUUID();
        try (PreparedStatement statement = connection.prepareStatement(INSERT_WORKFLOW)) {
            statement.setObject(1, workflowId);
            statement.setObject(2, UUID.randomUUID());
            statement.setString(3, "event-log-" + workflowId);
            statement.executeUpdate();
        }
        return workflowId;
    }
}
