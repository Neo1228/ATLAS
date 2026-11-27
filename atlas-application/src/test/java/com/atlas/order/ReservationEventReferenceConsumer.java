package com.atlas.order;

import com.atlas.application.service.impl.KafkaReservationEventPublisher;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.apache.kafka.clients.consumer.CloseOptions;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.errors.TimeoutException;
import org.apache.kafka.common.header.Header;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * ADR-0067이 소비자 책임으로 둔 중복 제거를 구현한 테스트 전용 참조 소비자. 운영 코드가 아니다.
 *
 * <p>규칙은 셋이다. 적용은 {@code (workflowId, aggregateVersion)}마다 한 번이고, offset commit은 적용이 끝난 뒤에
 * 따로 한다(at-least-once). 적용 여부는 PostgreSQL에 남기므로 소비자가 죽어도, 다른 인스턴스가 이어받아도 유지된다.
 * 적용 표시, 상태 갱신, 부수 효과는 한 트랜잭션이다. 셋째로 상태는 더 높은 aggregateVersion으로만 바뀐다. 높은 버전이
 * 낮은 버전보다 먼저 첫 전달돼도(EXP-091) 상태가 거꾸로 가지 않고, 이미 지나간 낮은 버전의 부수 효과는 내지 않는다.
 */
final class ReservationEventReferenceConsumer implements AutoCloseable {
    private static final JsonMapper MAPPER = JsonMapper.builder().build();
    private static final Duration CLOSE_TIMEOUT = Duration.ofSeconds(2);

    private final KafkaConsumer<String, String> consumer;
    private final String topic;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;
    private final List<String> receivedEventIds = new ArrayList<>();
    private int duplicatesSkipped;
    private int staleSkipped;

    ReservationEventReferenceConsumer(String bootstrapServers, String topic, String group, JdbcTemplate jdbc,
            int maxPollRecords) {
        this(bootstrapServers, topic, group, jdbc, maxPollRecords, Duration.ofSeconds(45));
    }

    /** sessionTimeout은 {@link #crash()}한 인스턴스를 그룹이 내보내는 데 걸리는 시간이다(broker 최소값 6초). */
    ReservationEventReferenceConsumer(String bootstrapServers, String topic, String group, JdbcTemplate jdbc,
            int maxPollRecords, Duration sessionTimeout) {
        this.consumer = new KafkaConsumer<>(Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers,
                ConsumerConfig.GROUP_ID_CONFIG, group,
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest",
                ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false,
                ConsumerConfig.MAX_POLL_RECORDS_CONFIG, maxPollRecords,
                ConsumerConfig.SESSION_TIMEOUT_MS_CONFIG, Math.toIntExact(sessionTimeout.toMillis()),
                ConsumerConfig.HEARTBEAT_INTERVAL_MS_CONFIG, Math.toIntExact(sessionTimeout.toMillis() / 3),
                ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class,
                ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class));
        this.topic = topic;
        this.jdbc = jdbc;
        this.transaction = new TransactionTemplate(new DataSourceTransactionManager(jdbc.getDataSource()));
        consumer.subscribe(List.of(topic));
    }

    /** 적용 표시(중복 제거 기준)와 부수 효과 기록 테이블. 부수 효과 테이블에는 유일 제약이 없어 중복 적용이 그대로 드러난다. */
    static void createSink(JdbcTemplate jdbc) {
        jdbc.execute("""
                CREATE TABLE reservation_event_applied (
                    workflow_id uuid NOT NULL, aggregate_version bigint NOT NULL,
                    PRIMARY KEY (workflow_id, aggregate_version))""");
        jdbc.execute("CREATE TABLE reservation_event_effect (workflow_id uuid NOT NULL, aggregate_version bigint NOT NULL)");
        jdbc.execute("""
                CREATE TABLE reservation_event_state (
                    workflow_id uuid PRIMARY KEY, aggregate_version bigint NOT NULL)""");
    }

    /** 한 묶음을 읽어 적용한다. offset은 commit하지 않는다. 받은 레코드 수를 돌려준다. */
    int pollAndApply(Duration timeout) {
        int received = 0;
        for (ConsumerRecord<String, String> record : consumer.poll(timeout)) {
            apply(record);
            received++;
        }
        return received;
    }

    /** 받은 위치까지 commit한다. broker가 응답하지 않아 시간이 지나면 false이고 다음 commit이 다시 시도한다. */
    boolean commit(Duration timeout) {
        try {
            consumer.commitSync(timeout);
            return true;
        } catch (TimeoutException exception) {
            return false;
        }
    }

    /** 이 소비자의 현재 위치 뒤에 남은 레코드 수. */
    long lag() {
        Set<TopicPartition> partitions = consumer.assignment();
        long lag = 0;
        for (var end : consumer.endOffsets(partitions).entrySet()) {
            lag += end.getValue() - consumer.position(end.getKey());
        }
        return lag;
    }

    Set<TopicPartition> assignment() {
        return consumer.assignment();
    }

    /** 높은 버전이 이미 반영돼 부수 효과를 내지 않고 넘긴 전달 수(첫 전달 순서 역행). */
    int staleSkipped() {
        return staleSkipped;
    }

    /** 그룹에서 나가지 않고 연결만 끊어 죽은 인스턴스를 흉내 낸다. broker는 세션 만료 뒤에야 파티션을 다른 인스턴스에 넘긴다. */
    void crash() {
        consumer.close(CloseOptions.timeout(CLOSE_TIMEOUT)
                .withGroupMembershipOperation(CloseOptions.GroupMembershipOperation.REMAIN_IN_GROUP));
    }

    /** 이 인스턴스가 받은 event-id를 받은 순서대로. 중복 전달도 그대로 들어 있다. */
    List<String> receivedEventIds() {
        return List.copyOf(receivedEventIds);
    }

    /** 이미 적용돼 건너뛴 전달 수. */
    int duplicatesSkipped() {
        return duplicatesSkipped;
    }

    String topic() {
        return topic;
    }

    @Override
    public void close() {
        consumer.close(CloseOptions.timeout(CLOSE_TIMEOUT));
    }

    private void apply(ConsumerRecord<String, String> record) {
        Header header = record.headers().lastHeader(KafkaReservationEventPublisher.EVENT_ID_HEADER);
        String eventId = new String(header.value(), StandardCharsets.UTF_8);
        JsonNode payload = MAPPER.readTree(record.value());
        // 모르는 계약 버전은 건너뛰지 않고 멈춘다. 건너뛰면 cursor(offset)가 지나가 그 이벤트를 잃는다(ADR-0103).
        int schemaVersion = payload.path("schemaVersion").asInt();
        if (schemaVersion != KafkaReservationEventPublisher.SCHEMA_VERSION) {
            throw new IllegalStateException("Unsupported reservation event schema version " + schemaVersion);
        }
        UUID workflowId = UUID.fromString(payload.path("workflowId").asString());
        long version = payload.path("aggregateVersion").asLong();
        receivedEventIds.add(eventId);
        boolean applied = Boolean.TRUE.equals(transaction.execute(status -> {
            int first = jdbc.update("""
                    INSERT INTO reservation_event_applied (workflow_id, aggregate_version) VALUES (?, ?)
                    ON CONFLICT DO NOTHING""", workflowId, version);
            if (first == 0) {
                return false;
            }
            // 상태는 더 높은 버전으로만 바뀐다. 이미 높은 버전이 반영됐다면 이 전달은 상태를 되돌리지 못한다.
            int advanced = jdbc.update("""
                    INSERT INTO reservation_event_state (workflow_id, aggregate_version) VALUES (?, ?)
                    ON CONFLICT (workflow_id) DO UPDATE SET aggregate_version = EXCLUDED.aggregate_version
                    WHERE reservation_event_state.aggregate_version < EXCLUDED.aggregate_version""", workflowId, version);
            if (advanced == 0) {
                staleSkipped++;
                return true;
            }
            jdbc.update("INSERT INTO reservation_event_effect (workflow_id, aggregate_version) VALUES (?, ?)",
                    workflowId, version);
            return true;
        }));
        if (!applied) {
            duplicatesSkipped++;
        }
    }
}
