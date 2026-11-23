package com.atlas.application.service;

import com.atlas.order.service.OrderInventoryEventLogService;
import com.atlas.order.service.OrderInventoryEventPublisher;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 예약 이벤트 로그를 읽어 publisher로 내보내는 relay worker. 기본은 꺼져 있다.
 *
 * <p>replica가 여러 개여도 cursor 행의 lease로 한 번에 하나만 relay한다. 순서는 lease 선점, 읽기, 전송, fenced cursor 전진이고
 * Kafka 전송은 DB 트랜잭션 밖에서 실행된다. 전송이 실패하면 cursor가 그대로 남아 다음 poll이 같은 묶음을 다시 보낸다
 * (중복은 있어도 유실은 없다).
 */
@Component
@ConditionalOnProperty(name = "atlas.order-inventory-reservation.event-relay.enabled", havingValue = "true")
public class OrderInventoryEventRelayWorker {
    private static final Logger LOGGER = LoggerFactory.getLogger(OrderInventoryEventRelayWorker.class);
    private static final Duration FAILURE_LOG_INTERVAL = Duration.ofMinutes(1);
    /** 한 poll에서 연속으로 비울 수 있는 묶음 수. 밀린 로그를 따라잡되 scheduler thread를 독점하지 않는다. */
    private static final int MAX_BATCHES_PER_POLL = 20;

    private final OrderInventoryEventLogService eventLog;
    private final OrderInventoryEventPublisher publisher;
    private final MeterRegistry meters;
    private final int batchSize;
    private final Duration leaseDuration;
    private final AtomicLong nextFailureLogNanos = new AtomicLong();

    public OrderInventoryEventRelayWorker(OrderInventoryEventLogService eventLog,
            OrderInventoryEventPublisher publisher, MeterRegistry meters,
            @Value("${atlas.order-inventory-reservation.event-relay.batch-size:500}") int batchSize,
            @Value("${atlas.order-inventory-reservation.event-relay.lease-duration:PT30S}") Duration leaseDuration,
            @Value("${atlas.order-inventory-reservation.event-relay.send-timeout:PT10S}") Duration sendTimeout) {
        if (batchSize < 1 || batchSize > 5000) throw new IllegalArgumentException("Invalid event relay batch size");
        // lease가 전송 한도보다 짧으면 전송 중에 lease가 만료되어 다른 replica가 같은 묶음을 중복 전송한다.
        if (leaseDuration.compareTo(sendTimeout.plusSeconds(5)) < 0) {
            throw new IllegalArgumentException("Event relay lease must exceed send timeout by at least 5 seconds");
        }
        this.eventLog = eventLog;
        this.publisher = publisher;
        this.meters = meters;
        this.batchSize = batchSize;
        this.leaseDuration = leaseDuration;
        Gauge.builder("atlas.order.inventory.event.relay.lag_events", eventLog,
                        value -> value.lagEvents(OrderInventoryEventLogService.RELAY_GROUP))
                .description("relay cursor 뒤에 쌓인 예약 이벤트 수").register(meters);
        Gauge.builder("atlas.order.inventory.event.relay.stalled_seconds", eventLog,
                        OrderInventoryEventLogService::stalledSeconds)
                .baseUnit("seconds")
                .description("커밋됐지만 오래 열린 트랜잭션의 xmin horizon 때문에 읽지 못하는 가장 오래된 이벤트의 나이")
                .register(meters);
    }

    @Scheduled(fixedDelayString = "${atlas.order-inventory-reservation.event-relay.poll-interval:PT0.2S}",
            scheduler = "orderInventoryEventRelayTaskScheduler")
    public void poll() {
        try {
            for (int batches = 0; batches < MAX_BATCHES_PER_POLL; batches++) {
                int published = eventLog.relay(batchSize, leaseDuration, publisher);
                if (published > 0) {
                    Counter.builder("atlas.order.inventory.event.relay.published").register(meters)
                            .increment(published);
                }
                if (published < batchSize) return;
            }
        } catch (RuntimeException exception) {
            Counter.builder("atlas.order.inventory.event.relay.failed").register(meters).increment();
            warnFailure(exception);
        }
    }

    private void warnFailure(RuntimeException exception) {
        long now = System.nanoTime();
        long next = nextFailureLogNanos.get();
        if ((next == 0 || now - next >= 0) && nextFailureLogNanos.compareAndSet(next,
                now + FAILURE_LOG_INTERVAL.toNanos())) {
            LOGGER.warn("Order inventory event relay failed failureType={}", exception.getClass().getSimpleName());
        }
    }
}
