package com.atlas.order.eventlog;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/** workflow 상태 변경이 같은 트랜잭션 안에서 빈틈없는 version의 이벤트 하나로 남는다는 불변식. */
@Testcontainers
class OrderInventoryEventTriggerIntegrationTest {

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18.6");

    @Test
    void everyStateChangeProducesExactlyOneEventWithConsecutiveVersions() {
        var dataSource = OrderInventoryEventLogSupport.migratedDataSource(POSTGRES);
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        TransactionTemplate transaction = new TransactionTemplate(new DataSourceTransactionManager(dataSource));

        UUID workflowId = OrderInventoryEventLogSupport.insertWorkflow(jdbc);
        assertThat(eventTypes(jdbc, workflowId)).containsExactly("RESERVATION_PENDING");

        jdbc.update("UPDATE order_inventory_reservation_workflow SET workflow_state = 'RETRY_PENDING', "
                + "last_error_code = 'LOCATION_TIMEOUT' WHERE workflow_id = ?", workflowId);
        jdbc.update("UPDATE order_inventory_reservation_workflow SET workflow_state = 'RESERVED', "
                + "last_error_code = NULL WHERE workflow_id = ?", workflowId);

        // 상태가 그대로이거나 상태 열을 건드리지 않는 UPDATE는 이벤트를 만들지 않는다.
        jdbc.update("UPDATE order_inventory_reservation_workflow SET workflow_state = 'RESERVED' "
                + "WHERE workflow_id = ?", workflowId);
        jdbc.update("UPDATE order_inventory_reservation_workflow SET attempt_count = attempt_count + 1 "
                + "WHERE workflow_id = ?", workflowId);

        // 롤백된 트랜잭션의 상태 변경은 이벤트도 함께 사라진다(상태와 이벤트가 원자적이다).
        transaction.executeWithoutResult(status -> {
            jdbc.update("UPDATE order_inventory_reservation_workflow SET workflow_state = 'CLAIMED' "
                    + "WHERE workflow_id = ?", workflowId);
            status.setRollbackOnly();
        });

        assertThat(eventTypes(jdbc, workflowId))
                .containsExactly("RESERVATION_PENDING", "RESERVATION_RETRY_PENDING", "RESERVATION_RESERVED");
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT aggregate_version, last_error_code FROM order_inventory_event
                WHERE workflow_id = ? ORDER BY aggregate_version
                """, workflowId);
        assertThat(rows).extracting(row -> ((Number) row.get("aggregate_version")).intValue())
                .containsExactly(1, 2, 3);
        assertThat(rows).extracting(row -> row.get("last_error_code")).containsExactly(null, "LOCATION_TIMEOUT", null);
    }

    private static List<String> eventTypes(JdbcTemplate jdbc, UUID workflowId) {
        return jdbc.queryForList("SELECT event_type FROM order_inventory_event WHERE workflow_id = ? "
                + "ORDER BY aggregate_version", String.class, workflowId);
    }
}
