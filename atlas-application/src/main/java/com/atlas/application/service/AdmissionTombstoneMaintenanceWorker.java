package com.atlas.application.service;

import com.atlas.admission.service.AdmissionService;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 종료된 admission ticket(tombstone)을 주기적으로 지운다(ADR-0088).
 *
 * <p>한 번에 batch 하나만 지운다. batch가 한 transaction의 잠금 수·WAL·실행 시간을 묶고, 같은 문장이 SKIP LOCKED라
 * 인스턴스가 여러 대여도 서로 기다리거나 같은 행을 두 번 지우지 않는다. 주문이 가리키는 ticket은 지우지 않는다.
 * 지우는 일은 되돌릴 수 없으므로 {@code atlas.admission.tombstone.purge-enabled=false}로 끌 수 있다.
 */
@Component
@ConditionalOnProperty(name = "atlas.admission.tombstone.purge-enabled", havingValue = "true", matchIfMissing = true)
public class AdmissionTombstoneMaintenanceWorker {
    private static final Logger LOGGER = LoggerFactory.getLogger(AdmissionTombstoneMaintenanceWorker.class);
    private static final long FAILURE_LOG_INTERVAL_NANOS = Duration.ofMinutes(5).toNanos();
    /** 보존 하한. 보존 기간은 같은 Idempotency-Key 재요청이 기존 ticket을 돌려받는 기간이기도 하다. */
    private static final Duration MIN_RETENTION = Duration.ofDays(1);

    private final AdmissionService admission;
    private final MeterRegistry meters;
    private final Duration retention;
    private final int batchSize;
    private final AtomicLong lastFailureLogNanos = new AtomicLong();

    public AdmissionTombstoneMaintenanceWorker(
            AdmissionService admission,
            MeterRegistry meters,
            @Value("${atlas.admission.tombstone.retention:P30D}") Duration retention,
            @Value("${atlas.admission.tombstone.purge-batch-size:500}") int batchSize) {
        this.admission = Objects.requireNonNull(admission);
        this.meters = Objects.requireNonNull(meters);
        this.retention = Objects.requireNonNull(retention);
        if (retention.compareTo(MIN_RETENTION) < 0) {
            throw new IllegalArgumentException("admission tombstone retention must be at least one day");
        }
        if (batchSize < 1 || batchSize > 5_000) {
            throw new IllegalArgumentException("admission tombstone purge batch size is invalid");
        }
        this.batchSize = batchSize;
    }

    @Scheduled(
            initialDelayString = "${atlas.admission.tombstone.purge-initial-delay:PT5M}",
            fixedDelayString = "${atlas.admission.tombstone.purge-interval:PT10M}")
    public void purgeTombstones() {
        try {
            int removed = admission.purgeTombstones(retention, batchSize);
            if (removed > 0) {
                Counter.builder("atlas.admission.tombstone.purged").register(meters).increment(removed);
            }
        } catch (RuntimeException exception) {
            // 정리 실패는 join·주문 경로를 막지 않는다. 다음 주기에 다시 시도한다.
            long now = System.nanoTime();
            long previous = lastFailureLogNanos.get();
            if ((previous == 0 || now - previous >= FAILURE_LOG_INTERVAL_NANOS)
                    && lastFailureLogNanos.compareAndSet(previous, now)) {
                LOGGER.warn("Admission tombstone purge failed; failureType={}", exception.getClass().getSimpleName());
            }
        }
    }
}
