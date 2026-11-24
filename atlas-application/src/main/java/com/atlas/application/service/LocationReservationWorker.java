package com.atlas.application.service;

import com.atlas.order.service.LocationReservationService;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(
        name = {"atlas.order-inventory-reservation.enabled", "atlas.order-inventory-reservation.recovery.worker-enabled"},
        havingValue = "true")
public class LocationReservationWorker {
    private static final Logger LOGGER = LoggerFactory.getLogger(LocationReservationWorker.class);
    private static final long FAILURE_LOG_INTERVAL_NANOS = Duration.ofMinutes(1).toNanos();

    private final LocationReservationService coordinator;
    private final MeterRegistry meters;
    private final int batchSize;
    private final AtomicLong nextFailureLogNanos = new AtomicLong();

    public LocationReservationWorker(LocationReservationService coordinator,
            MeterRegistry meters,
            @Value("${atlas.order-inventory-reservation.recovery.batch-size:100}")
            int batchSize) {
        if (batchSize < 1 || batchSize > 1000) throw new IllegalArgumentException("Invalid reservation batch size");
        this.coordinator = coordinator;
        this.meters = meters;
        this.batchSize = batchSize;
        Gauge.builder("atlas.order.inventory.recovery.oldest_due_age_seconds", coordinator,
                        LocationReservationService::oldestDueAgeSeconds)
                .tag("stage", "reservation").baseUnit("seconds").register(meters);
        Gauge.builder("atlas.order.inventory.needs_review", coordinator, value -> value.needsReviewCount(false))
                .tag("stage", "reservation").description("사람이 봐야 하는 예약 workflow 수").register(meters);
        Gauge.builder("atlas.order.inventory.needs_review_claimed", coordinator, value -> value.needsReviewCount(true))
                .tag("stage", "reservation")
                .description("NEEDS_REVIEW이면서 원격 재고가 CLAIMED로 남아 TTL로 풀리지 않는 workflow 수")
                .register(meters);
    }

    @Scheduled(fixedDelayString = "${atlas.order-inventory-reservation.recovery.poll-interval:PT1S}",
            scheduler = "orderInventoryRecoveryTaskScheduler")
    public void poll() {
        Timer.Sample sample = Timer.start(meters);
        try {
            int processed = coordinator.recoverDue(batchSize);
            if (processed > 0) Counter.builder("atlas.order.inventory.recovery.claimed")
                    .tag("stage", "reservation").register(meters).increment(processed);
            Counter.builder("atlas.order.inventory.recovery.polls").tag("stage", "reservation")
                    .tag("result", "success").register(meters).increment();
        } catch (RuntimeException exception) {
            Counter.builder("atlas.order.inventory.recovery.polls").tag("stage", "reservation")
                    .tag("result", "failure").register(meters).increment();
            warnFailure(exception);
        } finally {
            sample.stop(Timer.builder("atlas.order.inventory.recovery.duration")
                    .tag("stage", "reservation").register(meters));
        }
    }

    private void warnFailure(RuntimeException exception) {
        long now = System.nanoTime();
        long next = nextFailureLogNanos.get();
        if ((next == 0 || now - next >= 0) && nextFailureLogNanos.compareAndSet(next,
                now + FAILURE_LOG_INTERVAL_NANOS)) {
            LOGGER.warn("Order inventory reservation recovery poll failed failureType={}",
                    exception.getClass().getSimpleName());
        }
    }
}
