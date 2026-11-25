package com.atlas.application.service.impl;

import com.atlas.order.dto.response.ReservationWorkflowResponse;
import com.atlas.order.service.ReservationEventPublisher;
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
 *
 * <p>묶음은 {@link #CHUNK_BYTES} 이하의 덩어리로 나눠 덩어리마다 {@code flush}하고 모든 ack를 받은 뒤에 다음 덩어리를
 * 보낸다. 덩어리는 producer batch 크기(config의 {@code batch.size})의 절반이라 파티션마다 producer batch 하나에 담긴다.
 * 한 파티션에 batch가 둘 이상 걸려 있으면 앞 batch만 전송 시한으로 만료되고 뒤 batch는 살아남아 먼저 기록될 수 있고
 * (producer가 epoch를 올려 sequence를 다시 매긴다), 실패한 묶음을 다시 보낼 때 낮은 버전이 높은 버전 뒤에 첫 전달된다
 * ([EXP-091](docs/03-experiments/EXP-091-kafka-first-delivery-order.md)). batch가 하나면 파티션 안에서 전부 기록되거나
 * 전부 실패하므로 기록된 레코드는 항상 묶음 순서의 앞부분이다.
 */
@Component
@ConditionalOnProperty(name = "atlas.order-inventory-reservation.event-relay.enabled", havingValue = "true")
public class KafkaReservationEventPublisher implements ReservationEventPublisher {
    public static final String EVENT_ID_HEADER = "event-id";
    /** 덩어리 하나의 추정 크기 상한. producer {@code batch.size}의 절반이라 추정 오차와 한 파티션 쏠림을 흡수한다. */
    public static final int CHUNK_BYTES = 64 * 1024;
    /** 레코드 헤더, key, 길이 필드 등 직렬화 payload 밖의 크기를 넉넉히 잡은 추정값. */
    private static final int RECORD_OVERHEAD_BYTES = 128;

    private final KafkaTemplate<String, String> kafka;
    private final String topic;
    private final Duration awaitTimeout;
    private final JsonMapper mapper = JsonMapper.builder().build();

    public KafkaReservationEventPublisher(KafkaTemplate<String, String> kafka,
            @Value("${atlas.order-inventory-reservation.event-relay.topic:atlas.order-inventory.events}") String topic,
            @Value("${atlas.order-inventory-reservation.event-relay.send-timeout:PT10S}") Duration sendTimeout) {
        if (topic.isBlank()) throw new IllegalArgumentException("Invalid event relay topic");
        this.kafka = kafka;
        this.topic = topic;
        // producer의 delivery timeout이 먼저 실패를 알리도록 조금 더 기다린다.
        this.awaitTimeout = sendTimeout.plusSeconds(1);
    }

    @Override
    public void publish(List<ReservationWorkflowResponse.ReservationEvent> batch) {
        long startedAt = System.nanoTime();
        List<CompletableFuture<?>> sends = new ArrayList<>();
        int chunkBytes = 0;
        for (ReservationWorkflowResponse.ReservationEvent event : batch) {
            String payload = payload(event);
            int size = payload.length() + RECORD_OVERHEAD_BYTES;
            if (!sends.isEmpty() && chunkBytes + size > CHUNK_BYTES) {
                awaitChunk(sends);
                sends.clear();
                chunkBytes = 0;
                // 다음 덩어리는 한도 안에서만 시작한다. 시작한 덩어리는 끝까지 기다리므로 최악의 소요는 한도의 두 배다.
                if (System.nanoTime() - startedAt > awaitTimeout.toNanos()) {
                    throw new IllegalStateException("Order inventory event publish exceeded the send timeout");
                }
            }
            ProducerRecord<String, String> record = new ProducerRecord<>(topic, event.workflowId().toString(), payload);
            record.headers().add(EVENT_ID_HEADER, event.eventId().getBytes(StandardCharsets.UTF_8));
            sends.add(kafka.send(record));
            chunkBytes += size;
        }
        awaitChunk(sends);
    }

    /**
     * 덩어리를 바로 내보내고 모든 ack(또는 실패)를 기다린다. 기다리는 한도({@code send-timeout + 1초})가 producer의 전송 시한
     * 보다 길어서 이 메서드가 정상적으로 돌아오거나 실패로 끝나면 덩어리의 batch는 이미 완료(기록 또는 폐기)된 것이다.
     */
    private void awaitChunk(List<CompletableFuture<?>> sends) {
        kafka.flush();
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

    private String payload(ReservationWorkflowResponse.ReservationEvent event) {
        return mapper.writeValueAsString(new Payload(event.eventId(), event.eventSeq(), event.workflowId().toString(),
                event.orderId().toString(), event.aggregateVersion(), event.eventType(), event.lastErrorCode(),
                event.occurredAt().toString()));
    }

    /** topic에 실리는 JSON 계약. 필드 이름을 바꾸면 소비자가 깨진다. */
    record Payload(String eventId, long eventSeq, String workflowId, String orderId, long aggregateVersion,
            String eventType, String lastErrorCode, String occurredAt) {
    }
}
