package com.atlas.order;

import static org.assertj.core.api.Assertions.assertThat;

import com.atlas.order.persistence.projection.ReservationEvent;
import com.atlas.order.repository.ReservationEventRepository;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * DB 안 소비자가 cursor로 이벤트를 읽을 때 지켜야 하는 계약을 확인한다. ADR-0067은 중복 제거와 적용 순서를 소비자
 * 책임으로 두었으므로, 소비자가 기댈 수 있는 성질(읽기 순서, 재읽기 동일성, cursor 단조 증가, 그룹별 독립)이
 * 실제로 성립해야 한다.
 */
@Testcontainers
class ReservationEventConsumerContractIntegrationTest {
    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18.6");

    @Test
    void readsInCommitOrderRereadsTheSameBatchUntilAdvancedAndNeverMovesBackward() {
        DataSource dataSource = ReservationEventTestSupport.migratedDataSource(POSTGRES);
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        ReservationEventRepository repository = new ReservationEventRepository(jdbc);
        String group = "contract-" + UUID.randomUUID();

        List<UUID> workflows = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            workflows.add(ReservationEventTestSupport.insertWorkflow(jdbc));
        }
        // 같은 workflow의 상태를 여러 번 바꿔 aggregate_version이 올라가게 한다.
        for (UUID workflowId : workflows) {
            jdbc.update("UPDATE order_inventory_reservation_workflow SET workflow_state = 'RESERVED' "
                    + "WHERE workflow_id = ?", workflowId);
        }

        List<ReservationEvent> first = repository.readEvents(group, 4);
        List<ReservationEvent> again = repository.readEvents(group, 4);
        assertThat(first).hasSize(4);
        assertThat(again).as("without advance, a re-read returns the same batch").isEqualTo(first);
        assertThat(isOrdered(first)).as("events come in (tx_id, event_seq) order").isTrue();

        repository.advance(group, first.get(first.size() - 1));
        List<ReservationEvent> next = repository.readEvents(group, 100);
        assertThat(next).isNotEmpty();
        assertThat(next.get(0).eventSeq()).as("the next batch starts after the cursor")
                .isGreaterThan(first.get(first.size() - 1).eventSeq());
        Set<String> seen = new HashSet<>();
        first.forEach(event -> seen.add(event.eventId()));
        next.forEach(event -> assertThat(seen.add(event.eventId()))
                .as("no event is delivered twice across advanced batches").isTrue());

        // 오래된 이벤트로 advance를 다시 호출해도 cursor는 뒤로 가지 않는다.
        ReservationEvent latest = next.get(next.size() - 1);
        repository.advance(group, latest);
        repository.advance(group, first.get(0));
        assertThat(repository.readEvents(group, 100)).as("cursor did not move backward").isEmpty();
        assertThat(repository.lagEvents(group)).isZero();
    }

    @Test
    void consumerGroupsKeepIndependentCursors() {
        DataSource dataSource = ReservationEventTestSupport.migratedDataSource(POSTGRES);
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        ReservationEventRepository repository = new ReservationEventRepository(jdbc);
        UUID workflowId = ReservationEventTestSupport.insertWorkflow(jdbc);
        jdbc.update("UPDATE order_inventory_reservation_workflow SET workflow_state = 'RESERVED' "
                + "WHERE workflow_id = ?", workflowId);

        String fast = "fast-" + UUID.randomUUID();
        String slow = "slow-" + UUID.randomUUID();
        List<ReservationEvent> all = repository.readEvents(fast, 100);
        assertThat(all).isNotEmpty();
        repository.advance(fast, all.get(all.size() - 1));

        assertThat(repository.readEvents(fast, 100)).as("fast group is caught up").isEmpty();
        assertThat(repository.readEvents(slow, 100)).as("slow group still sees every event").hasSameSizeAs(all);
        assertThat(repository.lagEvents(slow)).isEqualTo(all.size());
    }

    private static boolean isOrdered(List<ReservationEvent> events) {
        for (int i = 1; i < events.size(); i++) {
            ReservationEvent previous = events.get(i - 1);
            ReservationEvent current = events.get(i);
            if (current.eventSeq() <= previous.eventSeq()) return false;
        }
        return true;
    }
}
