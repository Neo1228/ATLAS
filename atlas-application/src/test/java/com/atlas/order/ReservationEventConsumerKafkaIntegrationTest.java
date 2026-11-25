package com.atlas.order;

import static org.assertj.core.api.Assertions.assertThat;

import com.atlas.application.config.ReservationEventConfiguration;
import com.atlas.application.service.ReservationEventWorker;
import com.atlas.application.service.impl.KafkaReservationEventPublisher;
import com.atlas.order.repository.ReservationEventRepository;
import com.atlas.order.service.ReservationEventService;
import com.atlas.order.service.impl.ReservationEventServiceImpl;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import javax.sql.DataSource;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * relay가 보낸 토픽을 소비자가 읽을 때의 불변식(EXP-082): 유실 없음, 중복 전달은 한 번만 적용, 같은 그룹의 두 인스턴스가
 * 겹쳐 적용하지 않음, 재시작은 commit한 offset부터 이어 읽음, broker 중단 뒤 복구, 리밸런스와 첫 전달 순서 역행(EXP-091)
 * 아래에서도 상태가 거꾸로 가지 않음. 소비자는 테스트 전용
 * {@link ReservationEventReferenceConsumer}다. 적용 횟수는 부수 효과 테이블의 행으로 센다.
 */
@Testcontainers
class ReservationEventConsumerKafkaIntegrationTest {
    private static final Duration DEADLINE = Duration.ofSeconds(60);
    private static final Duration POLL = Duration.ofMillis(200);
    private static final Duration COMMIT_TIMEOUT = Duration.ofSeconds(2);

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18.6");

    @Container
    static final KafkaContainer KAFKA = new KafkaContainer("apache/kafka:3.9.1");

    @Test
    void duplicateDeliveryLosesNothingAndEachEventIsAppliedOnce() throws Exception {
        Scenario scenario = new Scenario(1, 12);
        try (Relay relay = scenario.relay(1)) {
            relay.poll();
            relay.poll();
        }
        try (ReservationEventReferenceConsumer consumer = scenario.consumer(5)) {
            scenario.drain(true, consumer);

            assertThat(scenario.effectsPerEvent()).as("모든 이벤트가 정확히 한 번 적용").isEqualTo(scenario.expectedOnce());
            assertThat(consumer.receivedEventIds().size()).as("relay 재시도가 만든 중복이 실제로 전달됨")
                    .isGreaterThan(scenario.expected.size());
            assertThat(consumer.duplicatesSkipped())
                    .isEqualTo(consumer.receivedEventIds().size() - scenario.expected.size());
        }
    }

    @Test
    void twoConsumersInOneGroupNeverApplyAnEventTwiceAcrossARebalance() throws Exception {
        Scenario scenario = new Scenario(2, 24);
        try (Relay relay = scenario.relay(0)) {
            relay.poll();
        }
        // A는 적용만 하고 commit하지 않는다. B가 합류하면 A에서 B로 옮겨 간 파티션이 처음부터 다시 전달된다.
        try (ReservationEventReferenceConsumer a = scenario.consumer(5);
                ReservationEventReferenceConsumer b = scenario.consumer(5)) {
            scenario.until(() -> {
                a.pollAndApply(POLL);
                return a.assignment().size() == 2 && a.lag() == 0;
            });
            scenario.until(() -> {
                a.pollAndApply(POLL);
                b.pollAndApply(POLL);
                return a.assignment().size() == 1 && b.assignment().size() == 1;
            });
            scenario.drain(true, a, b);

            Set<TopicPartition> partitions = new HashSet<>(a.assignment());
            partitions.addAll(b.assignment());
            assertThat(partitions).as("파티션 둘이 두 인스턴스에 나뉨").hasSize(2);
            assertThat(scenario.effectsPerEvent()).isEqualTo(scenario.expectedOnce());
            assertThat(a.duplicatesSkipped() + b.duplicatesSkipped()).as("다른 인스턴스가 이미 적용한 전달은 건너뜀")
                    .isPositive();
        }
    }

    @Test
    void restartResumesFromTheCommittedOffsetAndRedeliveryAfterACrashIsAppliedOnce() throws Exception {
        Scenario scenario = new Scenario(1, 12);
        try (Relay relay = scenario.relay(0)) {
            relay.poll();
        }
        List<String> topicOrder = scenario.jdbc.queryForList("""
                SELECT workflow_id::text || ':' || aggregate_version FROM order_inventory_event
                ORDER BY tx_id, event_seq""", String.class);
        List<String> committed;
        try (ReservationEventReferenceConsumer first = scenario.consumer(5)) {
            scenario.until(() -> first.pollAndApply(POLL) > 0);
            assertThat(first.commit(DEADLINE)).isTrue();
            committed = first.receivedEventIds();
        }
        int resumeAt = committed.size();
        assertThat(committed).isEqualTo(topicOrder.subList(0, resumeAt));
        assertThat(resumeAt).isLessThan(topicOrder.size());

        int crashed;
        try (ReservationEventReferenceConsumer second = scenario.consumer(5)) {
            // 적용은 했지만 commit 전에 죽는다.
            scenario.until(() -> second.pollAndApply(POLL) > 0);
            assertThat(second.receivedEventIds()).as("commit한 offset부터 읽음")
                    .isEqualTo(topicOrder.subList(resumeAt, resumeAt + second.receivedEventIds().size()));
            crashed = second.receivedEventIds().size();
        }

        try (ReservationEventReferenceConsumer third = scenario.consumer(5)) {
            scenario.drain(true, third);

            assertThat(third.receivedEventIds()).as("commit한 offset 앞은 다시 읽지 않고 commit 못 한 구간은 다시 받음")
                    .isEqualTo(topicOrder.subList(resumeAt, topicOrder.size()));
            assertThat(third.duplicatesSkipped()).as("commit 못 한 구간은 이미 적용돼 건너뜀").isEqualTo(crashed);
            assertThat(scenario.effectsPerEvent()).isEqualTo(scenario.expectedOnce());
            assertThat(scenario.committedRecords()).isEqualTo(topicOrder.size());
        }
    }

    @Test
    void rebalanceWithACrashedInstanceAndInvertedFirstDeliveryKeepsStateMonotonicAndEffectsOnce() throws Exception {
        InvertedTopic input = new InvertedTopic(3, 30);
        // A가 먼저 일부를 적용한다(commit 없음). B가 합류하면 파티션이 나뉘고, 그 뒤 A가 그룹을 떠나지 않은 채 죽는다.
        // 세션이 만료되면 B가 모든 파티션을 commit된 위치(없음)부터 이어받아 처음부터 다시 읽는다.
        try (ReservationEventReferenceConsumer a = input.consumer(7);
                ReservationEventReferenceConsumer b = input.consumer(7)) {
            input.until(() -> a.pollAndApply(POLL) > 0);
            int appliedByA = a.receivedEventIds().size();
            input.until(() -> {
                a.pollAndApply(POLL);
                b.pollAndApply(POLL);
                return !a.assignment().isEmpty() && !b.assignment().isEmpty()
                        && a.assignment().size() + b.assignment().size() == 3;
            });
            a.crash();

            // A의 파티션까지 B에 넘어온 뒤(세션 만료)에도 끝까지 읽어야 한다. 넘어오기 전에 상태가 이미 맞아 보일 수 있다.
            input.until(() -> {
                b.pollAndApply(POLL);
                b.commit(DEADLINE);
                return b.assignment().size() == 3 && b.lag() == 0 && input.statePerWorkflow().equals(input.expectedState());
            });

            assertThat(appliedByA).as("A가 죽기 전에 이미 일부를 적용").isPositive();
            assertThat(b.assignment()).as("A의 세션이 만료돼 B가 모든 파티션을 이어받음").hasSize(3);
            assertThat(input.statePerWorkflow()).as("높은 버전이 먼저 도착해도 상태는 최종 버전").isEqualTo(input.expectedState());
            assertThat(input.effectsPerEvent()).as("각 전달은 한 번만 효과를 내고 이미 지나간 낮은 버전은 효과가 없음")
                    .isEqualTo(input.expectedEffects());
            assertThat(a.staleSkipped() + b.staleSkipped()).as("역행해 도착한 낮은 버전은 인스턴스와 관계없이 정확히 한 번씩 건너뜀")
                    .isEqualTo(input.invertedWorkflows());
            assertThat(input.committedRecords()).isEqualTo(input.records);
        }
    }

    @Test
    void consumerRecoversAfterABrokerPauseWithoutLossOrDoubleApply() throws Exception {
        Scenario scenario = new Scenario(1, 12);
        try (Relay relay = scenario.relay(0)) {
            relay.poll();
        }
        try (ReservationEventReferenceConsumer consumer = scenario.consumer(5)) {
            scenario.until(() -> consumer.pollAndApply(POLL) > 0);
            KAFKA.getDockerClient().pauseContainerCmd(KAFKA.getContainerId()).exec();
            try {
                // 멈춘 동안에는 이미 받아 둔 레코드만 처리되고 commit은 시간 초과다. 예외로 소비자가 죽지 않는다.
                consumer.pollAndApply(POLL);
                assertThat(consumer.commit(COMMIT_TIMEOUT)).as("broker가 멈춘 동안 commit은 시간 초과").isFalse();
            } finally {
                KAFKA.getDockerClient().unpauseContainerCmd(KAFKA.getContainerId()).exec();
            }

            scenario.drain(true, consumer);

            assertThat(scenario.effectsPerEvent()).isEqualTo(scenario.expectedOnce());
            assertThat(consumer.receivedEventIds()).hasSize(scenario.expected.size());
            assertThat(scenario.committedRecords()).as("복구 뒤 commit이 토픽 끝까지 감").isEqualTo(scenario.expected.size());
        }
    }

    /** 테스트마다 새 스키마, 새 토픽, workflow와 이벤트를 만든다. 그룹 id는 토픽 이름에 묶는다. */
    private static final class Scenario {
        final DataSource dataSource = ReservationEventTestSupport.migratedDataSource(POSTGRES);
        final JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        final String topic = "atlas.order-inventory.events.consumer-" + UUID.randomUUID();
        final String group = "group-" + topic;
        final Set<String> expected;

        Scenario(int partitions, int workflows) throws Exception {
            try (Admin admin = Admin.create(Map.of(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG,
                    KAFKA.getBootstrapServers()))) {
                admin.createTopics(List.of(new NewTopic(topic, partitions, (short) 1))).all().get();
            }
            // 절반은 RESERVED로 바꿔 workflow당 이벤트 1건 또는 2건.
            for (int i = 0; i < workflows; i++) {
                UUID workflowId = ReservationEventTestSupport.insertWorkflow(jdbc);
                if (i % 2 == 0) {
                    jdbc.update("UPDATE order_inventory_reservation_workflow SET workflow_state = 'RESERVED' "
                            + "WHERE workflow_id = ?", workflowId);
                }
            }
            ReservationEventReferenceConsumer.createSink(jdbc);
            expected = new HashSet<>(jdbc.queryForList(
                    "SELECT workflow_id::text || ':' || aggregate_version FROM order_inventory_event", String.class));
            assertThat(expected).hasSize(workflows + workflows / 2);
        }

        Relay relay(int failuresAfterSend) {
            return new Relay(dataSource, jdbc, topic, failuresAfterSend);
        }

        ReservationEventReferenceConsumer consumer(int maxPollRecords) {
            return new ReservationEventReferenceConsumer(KAFKA.getBootstrapServers(), topic, group, jdbc,
                    maxPollRecords);
        }

        /** 그룹이 commit한 offset의 합. 파티션마다 commit한 위치가 곧 소비를 마친 레코드 수다. */
        long committedRecords() throws Exception {
            try (Admin admin = Admin.create(Map.of(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG,
                    KAFKA.getBootstrapServers()))) {
                return admin.listConsumerGroupOffsets(group).partitionsToOffsetAndMetadata().get().values().stream()
                        .mapToLong(OffsetAndMetadata::offset).sum();
            }
        }

        Map<String, Integer> effectsPerEvent() {
            Map<String, Integer> effects = new HashMap<>();
            jdbc.query("""
                    SELECT workflow_id::text || ':' || aggregate_version AS event_id, count(*) AS applied
                    FROM reservation_event_effect GROUP BY 1""",
                    rs -> {
                        effects.put(rs.getString("event_id"), rs.getInt("applied"));
                    });
            return effects;
        }

        Map<String, Integer> expectedOnce() {
            Map<String, Integer> once = new HashMap<>();
            expected.forEach(eventId -> once.put(eventId, 1));
            return once;
        }

        /** 모든 이벤트가 적용되고 모든 소비자가 토픽 끝에 닿을 때까지 읽는다. */
        void drain(boolean commit, ReservationEventReferenceConsumer... consumers) {
            until(() -> {
                boolean caughtUp = true;
                for (ReservationEventReferenceConsumer consumer : consumers) {
                    consumer.pollAndApply(POLL);
                    if (commit) {
                        consumer.commit(DEADLINE);
                    }
                    caughtUp &= consumer.lag() == 0;
                }
                return caughtUp && effectsPerEvent().size() == expected.size();
            });
        }

        void until(BooleanSupplier condition) {
            long deadline = System.nanoTime() + DEADLINE.toNanos();
            while (!condition.getAsBoolean()) {
                if (System.nanoTime() > deadline) {
                    throw new AssertionError("조건이 " + DEADLINE + " 안에 충족되지 않았다");
                }
            }
        }
    }

    /**
     * relay를 거치지 않고 만든 합성 토픽. 홀수 번째 workflow는 v2를 v1보다 먼저 싣고(첫 전달 순서 역행), 앞쪽 workflow
     * 일부는 통째로 한 번 더 싣는다(중복). 같은 키는 같은 파티션이라 파티션 안 순서는 쓴 순서와 같다.
     */
    private static final class InvertedTopic {
        final DataSource dataSource = ReservationEventTestSupport.migratedDataSource(POSTGRES);
        final JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        final String topic = "atlas.order-inventory.events.rebalance-" + UUID.randomUUID();
        final String group = "group-" + topic;
        final List<UUID> workflows = new ArrayList<>();
        final int records;

        InvertedTopic(int partitions, int workflowCount) throws Exception {
            try (Admin admin = Admin.create(Map.of(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG,
                    KAFKA.getBootstrapServers()))) {
                admin.createTopics(List.of(new NewTopic(topic, partitions, (short) 1))).all().get();
            }
            ReservationEventReferenceConsumer.createSink(jdbc);
            int sent = 0;
            try (KafkaProducer<String, String> producer = new KafkaProducer<>(Map.of(
                    ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers(),
                    ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true,
                    ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class,
                    ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class))) {
                for (int pass = 0; pass < 2; pass++) {
                    for (int i = 0; i < workflowCount; i++) {
                        if (pass == 1 && i >= workflowCount / 3) {
                            break;
                        }
                        UUID workflowId = pass == 0 ? UUID.randomUUID() : workflows.get(i);
                        if (pass == 0) {
                            workflows.add(workflowId);
                        }
                        int[] versions = i % 2 == 1 ? new int[] {2, 1} : new int[] {1, 2};
                        for (int version : versions) {
                            send(producer, workflowId, version);
                            sent++;
                        }
                    }
                }
                producer.flush();
            }
            records = sent;
        }

        private void send(KafkaProducer<String, String> producer, UUID workflowId, int version) {
            ProducerRecord<String, String> record = new ProducerRecord<>(topic, workflowId.toString(),
                    "{\"workflowId\":\"" + workflowId + "\",\"aggregateVersion\":" + version + "}");
            record.headers().add(KafkaReservationEventPublisher.EVENT_ID_HEADER,
                    (workflowId + ":" + version).getBytes(StandardCharsets.UTF_8));
            producer.send(record);
        }

        ReservationEventReferenceConsumer consumer(int maxPollRecords) {
            return new ReservationEventReferenceConsumer(KAFKA.getBootstrapServers(), topic, group, jdbc,
                    maxPollRecords, Duration.ofSeconds(6));
        }

        Map<String, Long> statePerWorkflow() {
            Map<String, Long> state = new HashMap<>();
            jdbc.query("SELECT workflow_id::text AS id, aggregate_version AS version FROM reservation_event_state",
                    rs -> {
                        state.put(rs.getString("id"), rs.getLong("version"));
                    });
            return state;
        }

        /** v2가 v1보다 먼저 실린 workflow 수. 소비자 전체가 그만큼의 낮은 버전을 이미 지난 것으로 건너뛴다. */
        long invertedWorkflows() {
            return workflows.size() / 2;
        }

        Map<String, Long> expectedState() {
            Map<String, Long> state = new HashMap<>();
            workflows.forEach(id -> state.put(id.toString(), 2L));
            return state;
        }

        Map<String, Integer> effectsPerEvent() {
            Map<String, Integer> effects = new HashMap<>();
            jdbc.query("""
                    SELECT workflow_id::text || ':' || aggregate_version AS event_id, count(*) AS applied
                    FROM reservation_event_effect GROUP BY 1""", rs -> {
                effects.put(rs.getString("event_id"), rs.getInt("applied"));
            });
            return effects;
        }

        /** v1이 v2보다 먼저 도착한 workflow는 v1·v2 모두 한 번, v2가 먼저 도착한 workflow는 v2만 한 번이다. */
        Map<String, Integer> expectedEffects() {
            Map<String, Integer> effects = new HashMap<>();
            for (int i = 0; i < workflows.size(); i++) {
                effects.put(workflows.get(i) + ":2", 1);
                if (i % 2 == 0) {
                    effects.put(workflows.get(i) + ":1", 1);
                }
            }
            return effects;
        }

        long committedRecords() throws Exception {
            try (Admin admin = Admin.create(Map.of(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG,
                    KAFKA.getBootstrapServers()))) {
                return admin.listConsumerGroupOffsets(group).partitionsToOffsetAndMetadata().get().values().stream()
                        .mapToLong(OffsetAndMetadata::offset).sum();
            }
        }

        void until(BooleanSupplier condition) {
            long deadline = System.nanoTime() + DEADLINE.toNanos();
            while (!condition.getAsBoolean()) {
                if (System.nanoTime() > deadline) {
                    throw new AssertionError("조건이 " + DEADLINE + " 안에 충족되지 않았다");
                }
            }
        }
    }

    /** 실제 relay(worker, repository, Kafka publisher)를 한 번 구성한다. 앞의 몇 번은 전송 뒤 실패시켜 중복 전달을 만든다. */
    private static final class Relay implements AutoCloseable {
        private final DefaultKafkaProducerFactory<String, String> producers;
        private final AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext();
        private final ReservationEventWorker worker;

        Relay(DataSource dataSource, JdbcTemplate jdbc, String topic, int failuresAfterSend) {
            ReservationEventConfiguration configuration = new ReservationEventConfiguration();
            producers = configuration.reservationEventProducerFactory(KAFKA.getBootstrapServers(),
                    Duration.ofSeconds(10));
            context.register(TransactionConfiguration.class);
            context.registerBean("dataSource", DataSource.class, () -> dataSource);
            context.registerBean(JdbcTemplate.class, () -> jdbc);
            context.registerBean("transactionManager", PlatformTransactionManager.class,
                    () -> new DataSourceTransactionManager(dataSource));
            context.registerBean(ReservationEventRepository.class);
            context.registerBean(ReservationEventServiceImpl.class);
            context.refresh();
            KafkaReservationEventPublisher kafkaPublisher = new KafkaReservationEventPublisher(
                    configuration.reservationEventKafkaTemplate(producers), topic, Duration.ofSeconds(10));
            worker = new ReservationEventWorker(context.getBean(ReservationEventService.class),
                    new FailingAfterSendPublisher(kafkaPublisher, failuresAfterSend), new SimpleMeterRegistry(), 4,
                    Duration.ofSeconds(30), Duration.ofSeconds(10));
        }

        void poll() {
            worker.poll();
        }

        @Override
        public void close() {
            context.close();
            producers.destroy();
        }
    }

    @Configuration
    @EnableTransactionManagement
    static class TransactionConfiguration {
    }
}
