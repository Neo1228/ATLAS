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
 * <p>규칙은 둘이다. 적용은 {@code (workflowId, aggregateVersion)}마다 한 번이고, offset commit은 적용이 끝난 뒤에
 * 따로 한다(at-least-once). 적용 여부는 PostgreSQL에 남기므로 소비자가 죽어도, 다른 인스턴스가 이어받아도 유지된다.
 * 적용 표시와 부수 효과는 한 트랜잭션이다.
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

    ReservationEventReferenceConsumer(String bootstrapServers, String topic, String group, JdbcTemplate jdbc,
            int maxPollRecords) {
        this.consumer = new KafkaConsumer<>(Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers,
                ConsumerConfig.GROUP_ID_CONFIG, group,
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest",
                ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false,
                ConsumerConfig.MAX_POLL_RECORDS_CONFIG, maxPollRecords,
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
            jdbc.update("INSERT INTO reservation_event_effect (workflow_id, aggregate_version) VALUES (?, ?)",
                    workflowId, version);
            return true;
        }));
        if (!applied) {
            duplicatesSkipped++;
        }
    }
}
