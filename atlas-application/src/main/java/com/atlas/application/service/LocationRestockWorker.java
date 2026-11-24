package com.atlas.application.service;

import com.atlas.order.service.LocationRestockService;
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

/** 환불·반품 transaction에서 기록된 원격 재입고 의도를 독립적으로 재시도한다. */
@Component
@ConditionalOnProperty(name = {"atlas.order-inventory-reservation.enabled",
        "atlas.order-inventory-reservation.recovery.worker-enabled"}, havingValue = "true")
public class LocationRestockWorker {
    private static final Logger LOGGER = LoggerFactory.getLogger(LocationRestockWorker.class);
    private static final long FAILURE_LOG_INTERVAL_NANOS = Duration.ofMinutes(1).toNanos();

    private final LocationRestockService restocks;
    private final MeterRegistry meters;
    private final int batchSize;
    private final AtomicLong nextFailureLogNanos = new AtomicLong();

    public LocationRestockWorker(LocationRestockService restocks, MeterRegistry meters,
            @Value("${atlas.order-inventory-reservation.recovery.batch-size:100}")
            int batchSize) {
        if (batchSize < 1 || batchSize > 1000) throw new IllegalArgumentException("Invalid restock batch size");
        this.restocks = restocks;
        this.meters = meters;
        this.batchSize = batchSize;
        Gauge.builder("atlas.order.inventory.recovery.oldest_due_age_seconds", restocks,
                        LocationRestockService::oldestDueAgeSeconds)
                .tag("stage", "restock").baseUnit("seconds").register(meters);
    }

    @Scheduled(fixedDelayString = "${atlas.order-inventory-reservation.recovery.poll-interval:PT1S}",
            scheduler = "orderInventoryRecoveryTaskScheduler")
    public void poll() {
        Timer.Sample sample = Timer.start(meters);
        try {
            int processed = restocks.recoverDue(batchSize);
            if (processed > 0) Counter.builder("atlas.order.inventory.recovery.claimed")
                    .tag("stage", "restock").register(meters).increment(processed);
            Counter.builder("atlas.order.inventory.recovery.polls").tag("stage", "restock")
                    .tag("result", "success").register(meters).increment();
        } catch (RuntimeException exception) {
            Counter.builder("atlas.order.inventory.recovery.polls").tag("stage", "restock")
                    .tag("result", "failure").register(meters).increment();
            warnFailure(exception);
        } finally {
            sample.stop(Timer.builder("atlas.order.inventory.recovery.duration")
                    .tag("stage", "restock").register(meters));
        }
    }

    private void warnFailure(RuntimeException exception) {
        long now = System.nanoTime();
        long next = nextFailureLogNanos.get();
        if ((next == 0 || now - next >= 0) && nextFailureLogNanos.compareAndSet(next,
                now + FAILURE_LOG_INTERVAL_NANOS)) {
            LOGGER.warn("Remote inventory restock poll failed failureType={}", exception.getClass().getSimpleName());
        }
    }
}
