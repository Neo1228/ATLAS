package com.atlas.application.service;

import com.atlas.order.persistence.state.BuyerCheckoutState;
import com.atlas.order.service.CheckoutFinalizationService;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 예약 workflow 처리와 독립적으로 checkout intent의 완료·취소 상태를 이어간다. */
@Component
@ConditionalOnProperty(name = {"atlas.order-inventory-reservation.enabled",
        "atlas.order-inventory-reservation.recovery.worker-enabled"}, havingValue = "true")
public class CheckoutFinalizationWorker {
    private static final Logger LOGGER = LoggerFactory.getLogger(CheckoutFinalizationWorker.class);
    private static final long FAILURE_LOG_INTERVAL_NANOS = Duration.ofMinutes(1).toNanos();

    private final CheckoutFinalizationService finalizer;
    private final MeterRegistry meters;
    private final int batchSize;
    private final AtomicLong nextFailureLogNanos = new AtomicLong();

    public CheckoutFinalizationWorker(CheckoutFinalizationService finalizer, MeterRegistry meters,
            @Value("${atlas.order-inventory-reservation.recovery.batch-size:100}")
            int batchSize) {
        if (batchSize < 1 || batchSize > 1000) throw new IllegalArgumentException("Invalid finalizer batch size");
        this.finalizer = finalizer;
        this.meters = meters;
        this.batchSize = batchSize;
        for (BuyerCheckoutState state : List.of(
                BuyerCheckoutState.RESERVATION_PENDING, BuyerCheckoutState.CANCEL_PENDING)) {
            Gauge.builder("atlas.order.inventory.recovery.oldest_pending_age_seconds", finalizer,
                            service -> service.oldestPendingAgeSeconds(state))
                    .tag("stage", "checkout-finalization")
                    .tag("state", state.name())
                    .baseUnit("seconds")
                    .register(meters);
        }
    }

    @Scheduled(fixedDelayString = "${atlas.order-inventory-reservation.recovery.poll-interval:PT1S}",
            scheduler = "orderInventoryRecoveryTaskScheduler")
    public void poll() {
        Timer.Sample sample = Timer.start(meters);
        try {
            int processed = finalizer.finalizeDue(batchSize);
            if (processed > 0) Counter.builder("atlas.order.inventory.recovery.processed")
                    .tag("stage", "checkout-finalization").register(meters).increment(processed);
            Counter.builder("atlas.order.inventory.recovery.polls").tag("stage", "checkout-finalization")
                    .tag("result", "success").register(meters).increment();
        } catch (RuntimeException exception) {
            Counter.builder("atlas.order.inventory.recovery.polls").tag("stage", "checkout-finalization")
                    .tag("result", "failure").register(meters).increment();
            warnFailure(exception);
        } finally {
            sample.stop(Timer.builder("atlas.order.inventory.recovery.duration")
                    .tag("stage", "checkout-finalization").register(meters));
        }
    }

    private void warnFailure(RuntimeException exception) {
        long now = System.nanoTime();
        long next = nextFailureLogNanos.get();
        if ((next == 0 || now - next >= 0) && nextFailureLogNanos.compareAndSet(next,
                now + FAILURE_LOG_INTERVAL_NANOS)) {
            LOGGER.warn("Checkout reservation finalization poll failed failureType={}",
                    exception.getClass().getSimpleName());
        }
    }
}
