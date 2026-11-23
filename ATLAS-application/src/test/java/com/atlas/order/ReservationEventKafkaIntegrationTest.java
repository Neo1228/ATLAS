package com.atlas.order;

import static org.assertj.core.api.Assertions.assertThat;

import com.atlas.application.config.ReservationEventConfiguration;
import com.atlas.application.service.impl.KafkaReservationEventPublisher;
import com.atlas.application.service.ReservationEventWorker;
import com.atlas.order.repository.ReservationEventRepository;
import com.atlas.order.persistence.projection.ReservationEvent;
import com.atlas.order.service.ReservationEventService;
import com.atlas.order.service.impl.ReservationEventServiceImpl;
import com.atlas.order.dto.response.ReservationEventResponse;
import com.atlas.order.service.ReservationEventPublisher;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.header.Header;
import org.apache.kafka.common.serialization.StringDeserializer;
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
 * relay가 읽기, 전송, cursor 전진 순서를 지킨다는 불변식. 전송이 실패한 묶음은 cursor를 옮기지 않고,
 * 재시도가 모든 이벤트를 다시 전달한다(중복은 허용, 유실은 불허).
 */
@Testcontainers
class ReservationEventKafkaIntegrationTest {
    private static final String TOPIC = "atlas.order-inventory.events.test";

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18.6");

    @Container
    static final KafkaContainer KAFKA = new KafkaContainer("apache/kafka:3.9.1");

    @Test
    void failedPublishKeepsTheCursorAndTheRetryDeliversEveryEventWithoutLoss() throws Exception {
        DataSource dataSource = ReservationEventTestSupport.migratedDataSource(POSTGRES);
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        for (int i = 0; i < 6; i++) {
            UUID workflowId = ReservationEventTestSupport.insertWorkflow(jdbc);
            if (i % 2 == 0) {
                jdbc.update("UPDATE order_inventory_reservation_workflow SET workflow_state = 'RESERVED' "
                        + "WHERE workflow_id = ?", workflowId);
            }
        }
        Set<String> expected = new HashSet<>(jdbc.queryForList(
                "SELECT workflow_id::text || ':' || aggregate_version FROM order_inventory_event", String.class));
        assertThat(expected).hasSize(9);

        ReservationEventConfiguration configuration = new ReservationEventConfiguration();
        DefaultKafkaProducerFactory<String, String> producers = configuration.reservationEventProducerFactory(
                KAFKA.getBootstrapServers(), Duration.ofSeconds(10));
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.register(TransactionConfiguration.class);
            context.registerBean("dataSource", DataSource.class, () -> dataSource);
            context.registerBean(JdbcTemplate.class, () -> jdbc);
            context.registerBean("transactionManager", PlatformTransactionManager.class,
                    () -> new DataSourceTransactionManager(dataSource));
            context.registerBean(ReservationEventRepository.class);
            context.registerBean(ReservationEventServiceImpl.class);
            context.refresh();
            ReservationEventService eventLog = context.getBean(ReservationEventService.class);

            KafkaReservationEventPublisher kafkaPublisher = new KafkaReservationEventPublisher(
                    configuration.reservationEventKafkaTemplate(producers), TOPIC, Duration.ofSeconds(10));
            // 첫 묶음은 Kafka에 실제로 보낸 뒤 실패로 처리해, ack는 받았지만 cursor는 못 옮긴 상황을 만든다.
            FailingAfterSendPublisher publisher = new FailingAfterSendPublisher(kafkaPublisher, 1);
            SimpleMeterRegistry meters = new SimpleMeterRegistry();
            ReservationEventWorker worker = new ReservationEventWorker(
                    eventLog, publisher, meters, 4, Duration.ofSeconds(30), Duration.ofSeconds(10));

            worker.poll();
            assertThat(meters.counter("atlas.order.inventory.event.relay.failed").count()).isEqualTo(1.0);
            assertThat(eventLog.lagEvents(ReservationEventService.RELAY_GROUP))
                    .as("전송이 실패하면 cursor는 그대로").isEqualTo(9L);

            worker.poll();
            assertThat(eventLog.lagEvents(ReservationEventService.RELAY_GROUP)).isZero();
            assertThat(meters.counter("atlas.order.inventory.event.relay.published").count()).isEqualTo(9.0);

            // lease를 잃은 보유자는 cursor를 옮길 수 없다: A의 lease가 만료되고 B가 이어받으면 A의 늦은 전진은 버려진다.
            ReservationEventRepository repository = context.getBean(ReservationEventRepository.class);
            ReservationEventTestSupport.insertWorkflow(jdbc);
            String group = ReservationEventService.RELAY_GROUP;
            UUID staleHolder = UUID.randomUUID();
            UUID currentHolder = UUID.randomUUID();
            assertThat(repository.claimLease(group, staleHolder, Duration.ofSeconds(30))).isTrue();
            assertThat(repository.claimLease(group, currentHolder, Duration.ofSeconds(30)))
                    .as("lease가 살아 있는 동안 다른 relay는 선점 못 한다").isFalse();
            jdbc.update("UPDATE order_inventory_event_cursor SET lease_until = clock_timestamp() - interval '1 second' "
                    + "WHERE consumer_group = ?", group);
            assertThat(repository.claimLease(group, currentHolder, Duration.ofSeconds(30))).isTrue();
            List<ReservationEvent> pending = repository.readEvents(group, 10);
            assertThat(pending).hasSize(1);
            assertThat(repository.advanceFenced(group, pending.get(0), staleHolder)).isFalse();
            assertThat(repository.lagEvents(group)).as("낡은 lease는 cursor를 옮기지 못한다").isEqualTo(1L);
            assertThat(repository.advanceFenced(group, pending.get(0), currentHolder)).isTrue();
            assertThat(repository.lagEvents(group)).isZero();
        } finally {
            producers.destroy();
        }

        Set<String> delivered = new HashSet<>();
        int records = consumeAll(expected, delivered);
        assertThat(delivered).containsAll(expected);
        assertThat(records).as("실패한 묶음은 중복 전달된다").isGreaterThanOrEqualTo(expected.size());
    }

    private static int consumeAll(Set<String> expected, Set<String> delivered) {
        Map<String, Object> config = Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers(),
                ConsumerConfig.GROUP_ID_CONFIG, "relay-test-" + UUID.randomUUID(),
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest",
                ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false,
                ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class,
                ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        int records = 0;
        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(config)) {
            consumer.subscribe(List.of(TOPIC));
            long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
            while (!delivered.containsAll(expected) && System.nanoTime() < deadline) {
                for (ConsumerRecord<String, String> record : consumer.poll(Duration.ofMillis(500))) {
                    Header header = record.headers().lastHeader(KafkaReservationEventPublisher.EVENT_ID_HEADER);
                    String eventId = new String(header.value(), StandardCharsets.UTF_8);
                    assertThat(eventId).startsWith(record.key() + ":");
                    delivered.add(eventId);
                    records++;
                }
            }
        }
        return records;
    }

    /** 앞의 몇 번은 실제 전송을 마친 뒤 예외를 던져 relay가 cursor를 옮기지 못하게 한다. */
    private static final class FailingAfterSendPublisher implements ReservationEventPublisher {
        private final ReservationEventPublisher delegate;
        private final AtomicInteger failuresLeft;

        FailingAfterSendPublisher(ReservationEventPublisher delegate, int failures) {
            this.delegate = delegate;
            this.failuresLeft = new AtomicInteger(failures);
        }

        @Override
        public void publish(List<ReservationEventResponse> batch) {
            delegate.publish(batch);
            if (failuresLeft.getAndDecrement() > 0) throw new IllegalStateException("forced publish failure");
        }
    }

    @Configuration
    @EnableTransactionManagement
    static class TransactionConfiguration {
    }
}
