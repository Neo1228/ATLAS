package com.atlas.application.service;

import com.atlas.settlement.service.SettlementBalanceCheckpointService;
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
 * 판매자 잔액 마감을 주기적으로 앞으로 옮긴다(ADR-0104). 마감이 멈춰도 잔액은 정확하다(꼬리를 더 읽을 뿐). 그래서 실패는
 * 기록만 하고 다음 주기에 다시 한다. 한 주기에 batch 를 여러 번 돌려 배포 직후 이력 전체를 따라잡는다.
 */
@Component
@ConditionalOnProperty(name = "atlas.settlement.balance-checkpoint.enabled", havingValue = "true", matchIfMissing = true)
public class SettlementBalanceCheckpointWorker {
    private static final Logger LOGGER = LoggerFactory.getLogger(SettlementBalanceCheckpointWorker.class);
    private static final long FAILURE_LOG_INTERVAL_NANOS = Duration.ofMinutes(5).toNanos();

    private final SettlementBalanceCheckpointService checkpoints;
    private final MeterRegistry meters;
    private final int batchSize;
    private final int maxBatchesPerRun;
    private final Duration safetyLag;
    private final AtomicLong lastFailureLogNanos = new AtomicLong();

    public SettlementBalanceCheckpointWorker(
            SettlementBalanceCheckpointService checkpoints,
            MeterRegistry meters,
            @Value("${atlas.settlement.balance-checkpoint.batch-size:50000}") int batchSize,
            @Value("${atlas.settlement.balance-checkpoint.max-batches-per-run:20}") int maxBatchesPerRun,
            @Value("${atlas.settlement.balance-checkpoint.safety-lag:PT5M}") Duration safetyLag) {
        this.checkpoints = Objects.requireNonNull(checkpoints);
        this.meters = Objects.requireNonNull(meters);
        if (batchSize < 1 || batchSize > 1_000_000 || maxBatchesPerRun < 1 || maxBatchesPerRun > 1_000) {
            throw new IllegalArgumentException("settlement balance checkpoint batch settings are invalid");
        }
        if (safetyLag.compareTo(Duration.ofSeconds(1)) < 0) {
            throw new IllegalArgumentException("settlement balance checkpoint safety lag must be at least one second");
        }
        this.batchSize = batchSize;
        this.maxBatchesPerRun = maxBatchesPerRun;
        this.safetyLag = safetyLag;
    }

    @Scheduled(
            initialDelayString = "${atlas.settlement.balance-checkpoint.initial-delay:PT30S}",
            fixedDelayString = "${atlas.settlement.balance-checkpoint.poll-interval:PT10S}")
    public void fold() {
        try {
            for (int batch = 0; batch < maxBatchesPerRun; batch++) {
                if (!checkpoints.foldOnce(batchSize, safetyLag)) {
                    return;
                }
            }
        } catch (RuntimeException exception) {
            meters.counter("atlas.settlement.balance_checkpoint.failures").increment();
            long now = System.nanoTime();
            long previous = lastFailureLogNanos.get();
            if ((previous == 0 || now - previous >= FAILURE_LOG_INTERVAL_NANOS)
                    && lastFailureLogNanos.compareAndSet(previous, now)) {
                LOGGER.warn("Settlement balance checkpoint fold failed; failureType={}",
                        exception.getClass().getSimpleName());
            }
        }
    }
}
