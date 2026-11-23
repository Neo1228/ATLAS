package com.atlas.order;

import static org.assertj.core.api.Assertions.assertThat;

import com.atlas.order.repository.ReservationEventRepository;
import com.atlas.order.persistence.projection.ReservationEvent;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * xmin 안전 읽기 불변식. 임의 시간에 커밋하는 동시 writer와 오래 열린 트랜잭션이 있어도
 * 커밋된 이벤트는 하나도 건너뛰지 않고, 아직 열려 있는 트랜잭션의 행은 돌려주지 않는다.
 * load-tests/event-log-gap-check.sh가 보여 준 identity cursor의 유실(3,200건 중 1,013건)을 막는 장치다.
 */
@Testcontainers
class ReservationEventReadIntegrationTest {
    private static final String GROUP = "safe-read-test";
    private static final int WRITERS = 8;
    private static final int EVENTS_PER_WRITER = 40;

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18.6");

    @Test
    void readSafeNeverSkipsACommittedEventAndNeverReturnsAnOpenTransactionsRows() throws Exception {
        DataSource dataSource = ReservationEventTestSupport.migratedDataSource(POSTGRES);
        ReservationEventRepository repository = new ReservationEventRepository(new JdbcTemplate(dataSource));
        Set<String> committed = ConcurrentHashMap.newKeySet();
        ExecutorService pool = Executors.newFixedThreadPool(WRITERS + 1);
        try {
            try (Connection openTransaction = dataSource.getConnection()) {
                openTransaction.setAutoCommit(false);
                UUID openWorkflow = ReservationEventTestSupport.insertWorkflow(openTransaction);

                // 열린 트랜잭션이 horizon을 잡고 있는 동안 writer 8개가 모두 커밋한다.
                runWriters(pool, dataSource, committed);
                assertThat(committed).hasSize(WRITERS * EVENTS_PER_WRITER);
                assertThat(repository.readEvents(GROUP, 1000)).isEmpty();
                assertThat(repository.lagEvents(GROUP)).isEqualTo(committed.size());
                assertThat(repository.stalledSeconds()).isGreaterThan(0.0);

                openTransaction.commit();
                committed.add(eventId(openWorkflow));
            }
            assertThat(repository.stalledSeconds()).isZero();

            // 두 번째 물결: 소비자가 읽는 도중에 writer들이 임의 지연으로 뒤섞여 커밋한다.
            AtomicBoolean writersDone = new AtomicBoolean();
            Future<List<ReservationEvent>> consumer = pool.submit(() -> consume(repository, writersDone));
            runWriters(pool, dataSource, committed);
            writersDone.set(true);
            List<ReservationEvent> consumed = consumer.get(60, TimeUnit.SECONDS);

            Set<String> consumedIds = new HashSet<>();
            for (ReservationEvent event : consumed) consumedIds.add(event.eventId());
            assertThat(consumed).as("중복 없이 한 번씩").hasSameSizeAs(consumedIds);
            assertThat(consumedIds).isEqualTo(committed);
            for (int i = 1; i < consumed.size(); i++) {
                ReservationEvent previous = consumed.get(i - 1);
                ReservationEvent current = consumed.get(i);
                assertThat(current.txId() > previous.txId()
                        || current.txId() == previous.txId() && current.eventSeq() > previous.eventSeq())
                        .as("(tx_id, event_seq) 순서").isTrue();
            }
            assertThat(repository.lagEvents(GROUP)).isZero();
        } finally {
            pool.shutdownNow();
        }
    }

    private static List<ReservationEvent> consume(ReservationEventRepository repository,
            AtomicBoolean writersDone) throws InterruptedException {
        List<ReservationEvent> consumed = new ArrayList<>();
        while (true) {
            // 종료 신호를 읽은 뒤에 읽어야, 빈 결과가 "모두 소비함"을 뜻한다.
            boolean finished = writersDone.get();
            List<ReservationEvent> batch = repository.readEvents(GROUP, 50);
            if (!batch.isEmpty()) {
                consumed.addAll(batch);
                repository.advance(GROUP, batch.get(batch.size() - 1));
            } else if (finished) {
                return consumed;
            } else {
                Thread.sleep(2);
            }
        }
    }

    private static void runWriters(ExecutorService pool, DataSource dataSource, Set<String> committed)
            throws Exception {
        List<Callable<Void>> writers = new ArrayList<>();
        for (int writer = 0; writer < WRITERS; writer++) {
            writers.add(() -> {
                for (int i = 0; i < EVENTS_PER_WRITER; i++) {
                    writeOne(dataSource, committed);
                }
                return null;
            });
        }
        for (Future<Void> result : pool.invokeAll(writers)) result.get(60, TimeUnit.SECONDS);
    }

    private static void writeOne(DataSource dataSource, Set<String> committed) throws SQLException,
            InterruptedException {
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            UUID workflowId = ReservationEventTestSupport.insertWorkflow(connection);
            // 낮은 xid를 가진 트랜잭션이 더 늦게 커밋되는 상황을 만든다.
            Thread.sleep(ThreadLocalRandom.current().nextInt(0, 15));
            connection.commit();
            committed.add(eventId(workflowId));
        }
    }

    private static String eventId(UUID workflowId) {
        return workflowId + ":1";
    }
}
