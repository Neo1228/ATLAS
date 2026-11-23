package com.atlas.inventory.location.service;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "inventory.expiry.worker-enabled", havingValue = "true", matchIfMissing = true)
public class InventoryReservationExpiryWorker {
    private static final Logger LOGGER = LoggerFactory.getLogger(InventoryReservationExpiryWorker.class);
    private static final long FAILURE_LOG_INTERVAL_NANOS = Duration.ofMinutes(1).toNanos();
    private final InventoryReservationService reservations;
    private final int batchSize;
    private final AtomicLong nextFailureLogNanos = new AtomicLong();

    public InventoryReservationExpiryWorker(InventoryReservationService reservations,
            @Value("${inventory.expiry.batch-size:100}") int batchSize) {
        if (batchSize < 1 || batchSize > 1000) throw new IllegalArgumentException("Invalid inventory expiry batch size");
        this.reservations = reservations;
        this.batchSize = batchSize;
    }

    @Scheduled(fixedDelayString = "${inventory.expiry.poll-interval:PT1S}")
    public void poll() {
        try {
            reservations.expireDue(batchSize);
        } catch (RuntimeException exception) {
            long now = System.nanoTime();
            long next = nextFailureLogNanos.get();
            if ((next == 0 || now - next >= 0) && nextFailureLogNanos.compareAndSet(next,
                    now + FAILURE_LOG_INTERVAL_NANOS)) {
                LOGGER.warn("Inventory reservation expiry poll failed failureType={}",
                        exception.getClass().getSimpleName());
            }
        }
    }
}
