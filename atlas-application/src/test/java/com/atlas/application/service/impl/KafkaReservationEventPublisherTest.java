package com.atlas.application.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.atlas.order.dto.response.ReservationWorkflowResponse.ReservationEvent;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;

/**
 * 한 묶음이 여러 덩어리로 나뉠 때 앞 덩어리의 ack(또는 실패)를 받은 뒤에만 다음 덩어리를 producer에 넘기는지 확인한다.
 * 한 파티션에 batch가 둘 이상 걸려 있으면 전송 시한이 batch마다 따로 흘러 뒤 batch만 먼저 기록될 수 있기 때문이다(EXP-091).
 */
class KafkaReservationEventPublisherTest {
    private static final int EVENTS = 400;

    private final KafkaTemplate<String, String> kafka = mockKafka();
    private final List<CompletableFuture<SendResult<String, String>>> unacked = new ArrayList<>();
    private final List<Integer> unackedAtEachSend = new ArrayList<>();
    private boolean failEachFlush;

    @Test
    void laterChunksAreSentOnlyAfterEarlierChunksAreAcknowledged() {
        publisher().publish(events());

        assertThat(unackedAtEachSend).as("모든 이벤트를 보냄").hasSize(EVENTS);
        assertThat(unackedAtEachSend).as("한 덩어리(약 64KB)보다 많은 레코드가 한꺼번에 미확인이면 안 됨")
                .allMatch(count -> count <= 160);
        assertThat(unackedAtEachSend).as("묶음이 실제로 여러 덩어리로 나뉨").anyMatch(count -> count > 100);
    }

    @Test
    void aFailedChunkStopsTheBatchBeforeAnyLaterChunkIsSent() {
        failEachFlush = true;

        assertThatThrownBy(() -> publisher().publish(events())).isInstanceOf(IllegalStateException.class);

        assertThat(unackedAtEachSend).as("실패한 첫 덩어리 뒤 레코드는 보내지 않음").hasSizeLessThan(EVENTS / 2);
    }

    private KafkaReservationEventPublisher publisher() {
        return new KafkaReservationEventPublisher(kafka, "events", Duration.ofSeconds(10));
    }

    private KafkaTemplate<String, String> mockKafka() {
        @SuppressWarnings("unchecked")
        KafkaTemplate<String, String> template = mock(KafkaTemplate.class);
        when(template.send(ArgumentMatchers.<ProducerRecord<String, String>>any())).thenAnswer(invocation -> {
            CompletableFuture<SendResult<String, String>> future = new CompletableFuture<>();
            unacked.add(future);
            unackedAtEachSend.add(unacked.size());
            return future;
        });
        // flush는 broker ack(또는 전송 시한 만료)를 흉내 낸다. 보류 중인 future를 모두 끝낸다.
        doAnswer(invocation -> {
            for (CompletableFuture<SendResult<String, String>> future : unacked) {
                if (failEachFlush) {
                    future.completeExceptionally(new IllegalStateException("expired"));
                } else {
                    future.complete(null);
                }
            }
            unacked.clear();
            return null;
        }).when(template).flush();
        return template;
    }

    private static List<ReservationEvent> events() {
        List<ReservationEvent> events = new ArrayList<>();
        for (int i = 1; i <= EVENTS; i++) {
            events.add(new ReservationEvent(i, i, UUID.randomUUID(), UUID.randomUUID(), 1, "RESERVATION_PENDING", null,
                    Instant.now()));
        }
        return events;
    }
}
