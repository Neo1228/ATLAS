package com.atlas.application.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.atlas.order.dto.response.ReservationWorkflowResponse.ReservationEvent;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 예약 이벤트 topic의 공개 계약(ADR-0103)을 고정한다. 필드 이름, JSON 형식, key, 중복 제거 헤더를 바꾸면 이 시험이 실패한다.
 * 바꿔야 한다면 하위 호환 여부를 먼저 정하고, 깨지는 변경이면 {@code SCHEMA_VERSION}을 올린 뒤 이 기대값을 고친다.
 */
class ReservationEventPayloadContractTest {
    private static final UUID WORKFLOW = UUID.fromString("018f0000-0000-7000-8000-000000000001");
    private static final UUID ORDER = UUID.fromString("018f0000-0000-7000-8000-000000000002");
    private static final Instant OCCURRED = Instant.parse("2026-11-27T01:02:03.456789Z");

    private final List<ProducerRecord<String, String>> sent = new ArrayList<>();

    @Test
    void payloadCarriesExactlyTheVersionedPublicFields() {
        publish(new ReservationEvent(42, 7, WORKFLOW, ORDER, 3, "RESERVATION_CLAIMED", "LOCATION_TIMEOUT", OCCURRED));

        ProducerRecord<String, String> record = sent.getFirst();
        JsonNode payload = JsonMapper.builder().build().readTree(record.value());
        Map<String, String> fieldTypes = new LinkedHashMap<>();
        payload.properties().forEach(field -> fieldTypes.put(field.getKey(), field.getValue().getNodeType().name()));

        assertThat(fieldTypes).as("필드 집합과 JSON 형식").containsExactlyInAnyOrderEntriesOf(Map.of(
                "schemaVersion", "NUMBER",
                "eventId", "STRING",
                "eventSeq", "NUMBER",
                "workflowId", "STRING",
                "orderId", "STRING",
                "aggregateVersion", "NUMBER",
                "eventType", "STRING",
                "lastErrorCode", "STRING",
                "occurredAt", "STRING"));
        assertThat(payload.path("schemaVersion").asInt()).isEqualTo(1);
        assertThat(payload.path("eventId").asString()).isEqualTo(WORKFLOW + ":3");
        assertThat(payload.path("eventSeq").asLong()).isEqualTo(42);
        assertThat(payload.path("workflowId").asString()).isEqualTo(WORKFLOW.toString());
        assertThat(payload.path("orderId").asString()).isEqualTo(ORDER.toString());
        assertThat(payload.path("aggregateVersion").asLong()).isEqualTo(3);
        assertThat(payload.path("eventType").asString()).isEqualTo("RESERVATION_CLAIMED");
        assertThat(payload.path("lastErrorCode").asString()).isEqualTo("LOCATION_TIMEOUT");
        assertThat(Instant.parse(payload.path("occurredAt").asString())).as("ISO-8601 UTC, 마이크로초 보존")
                .isEqualTo(OCCURRED);
        assertThat(record.key()).as("파티션 key = workflowId(같은 workflow 순서 유지)").isEqualTo(WORKFLOW.toString());
        assertThat(new String(record.headers().lastHeader("event-id").value(), StandardCharsets.UTF_8))
                .as("중복 제거 헤더").isEqualTo(WORKFLOW + ":3");
    }

    @Test
    void absentErrorCodeIsAnExplicitNull() {
        publish(new ReservationEvent(1, 1, WORKFLOW, ORDER, 1, "RESERVATION_PENDING", null, OCCURRED));

        JsonNode payload = JsonMapper.builder().build().readTree(sent.getFirst().value());
        assertThat(payload.has("lastErrorCode")).as("필드를 빼지 않고 null로 싣는다").isTrue();
        assertThat(payload.get("lastErrorCode").isNull()).isTrue();
    }

    private void publish(ReservationEvent event) {
        @SuppressWarnings("unchecked")
        KafkaTemplate<String, String> kafka = mock(KafkaTemplate.class);
        when(kafka.send(ArgumentMatchers.<ProducerRecord<String, String>>any())).thenAnswer(invocation -> {
            sent.add(invocation.getArgument(0));
            return CompletableFuture.<SendResult<String, String>>completedFuture(null);
        });
        new KafkaReservationEventPublisher(kafka, "events", Duration.ofSeconds(10)).publish(List.of(event));
    }
}
