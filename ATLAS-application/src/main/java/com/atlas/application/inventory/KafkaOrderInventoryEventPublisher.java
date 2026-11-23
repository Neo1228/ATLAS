package com.atlas.application.inventory;

import com.atlas.order.service.OrderInventoryEvent;
import com.atlas.order.service.OrderInventoryEventPublisher;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * 안전하게 읽은 예약 이벤트를 Kafka topic에 보낸다.
 *
 * <p>key는 workflowId라서 같은 workflow의 이벤트는 같은 파티션에서 순서가 유지된다. 소비자는 중복 전달(at-least-once)을
 * {@code event-id} header({@code workflowId:aggregateVersion})로 거른다. 한 묶음을 전부 보낸 뒤 모든 broker ack를
 * 기다리고, 하나라도 실패하거나 시간이 지나면 예외를 던져 relay가 cursor를 옮기지 못하게 한다.
 */
@Component
@ConditionalOnProperty(name = "atlas.order-inventory-reservation.event-relay.enabled", havingValue = "true")
public class KafkaOrderInventoryEventPublisher implements OrderInventoryEventPublisher {
    public static final String EVENT_ID_HEADER = "event-id";

    private final KafkaTemplate<String, String> kafka;
    private final String topic;
    private final Duration awaitTimeout;
    private final JsonMapper mapper = JsonMapper.builder().build();

    public KafkaOrderInventoryEventPublisher(KafkaTemplate<String, String> kafka,
            @Value("${atlas.order-inventory-reservation.event-relay.topic:atlas.order-inventory.events}") String topic,
            @Value("${atlas.order-inventory-reservation.event-relay.send-timeout:PT10S}") Duration sendTimeout) {
        if (topic.isBlank()) throw new IllegalArgumentException("Invalid event relay topic");
        this.kafka = kafka;
        this.topic = topic;
        // producer의 delivery timeout이 먼저 실패를 알리도록 조금 더 기다린다.
        this.awaitTimeout = sendTimeout.plusSeconds(1);
    }

    @Override
    public void publish(List<OrderInventoryEvent> batch) {
        List<CompletableFuture<?>> sends = new ArrayList<>(batch.size());
        for (OrderInventoryEvent event : batch) {
            ProducerRecord<String, String> record =
                    new ProducerRecord<>(topic, event.workflowId().toString(), payload(event));
            record.headers().add(EVENT_ID_HEADER, event.eventId().getBytes(StandardCharsets.UTF_8));
            sends.add(kafka.send(record));
        }
        try {
            CompletableFuture.allOf(sends.toArray(new CompletableFuture<?>[0]))
                    .get(awaitTimeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Order inventory event publish interrupted", exception);
        } catch (ExecutionException | TimeoutException exception) {
            throw new IllegalStateException("Order inventory event publish failed", exception);
        }
    }

    private String payload(OrderInventoryEvent event) {
        return mapper.writeValueAsString(new Payload(event.eventId(), event.eventSeq(), event.workflowId().toString(),
                event.orderId().toString(), event.aggregateVersion(), event.eventType(), event.lastErrorCode(),
                event.occurredAt().toString()));
    }

    /** topic에 실리는 JSON 계약. 필드 이름을 바꾸면 소비자가 깨진다. */
    record Payload(String eventId, long eventSeq, String workflowId, String orderId, long aggregateVersion,
            String eventType, String lastErrorCode, String occurredAt) {
    }
}
