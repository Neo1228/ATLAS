package com.atlas.application.service;

import com.atlas.account.service.AuthAttemptLimiter;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 인증 시도 제한 카운터에서 오래된 row를 회수한다.
 *
 * <p>카운터는 고정 창을 재사용하므로 정확성은 창 비교로 유지된다. 이 sweep은 더 이상 쓰이지 않는
 * 주체·출처의 row를 지워 테이블 크기가 실제 활성 사용자 수준에 머물게 한다.
 */
@Component
public class AuthThrottleMaintenanceWorker {
    private static final Logger LOGGER = LoggerFactory.getLogger(AuthThrottleMaintenanceWorker.class);
    private static final long FAILURE_LOG_INTERVAL_NANOS = Duration.ofMinutes(5).toNanos();

    private final AuthAttemptLimiter limiter;
    private final MeterRegistry meters;
    private final Clock clock;
    private final Duration retention;
    private final int batchSize;
    private final AtomicLong lastFailureLogNanos = new AtomicLong();

    public AuthThrottleMaintenanceWorker(
            AuthAttemptLimiter limiter,
            MeterRegistry meters,
            Clock clock,
            @Value("${atlas.auth.throttle.retention:PT24H}") Duration retention,
            @Value("${atlas.auth.throttle.purge-batch-size:500}") int batchSize) {
        this.limiter = Objects.requireNonNull(limiter);
        this.meters = Objects.requireNonNull(meters);
        this.clock = Objects.requireNonNull(clock);
        this.retention = Objects.requireNonNull(retention);
        if (batchSize < 1 || batchSize > 5_000) {
            throw new IllegalArgumentException("auth throttling purge batch size is invalid");
        }
        this.batchSize = batchSize;
    }

    @Scheduled(
            initialDelayString = "${atlas.auth.throttle.purge-initial-delay:PT5M}",
            fixedDelayString = "${atlas.auth.throttle.purge-interval:PT10M}")
    public void purgeStaleCounters() {
        try {
            int removed = limiter.purgeStale(clock.instant(), retention, batchSize);
            if (removed > 0) {
                Counter.builder("atlas.auth.throttle.purged").register(meters).increment(removed);
            }
        } catch (RuntimeException exception) {
            // sweep 실패는 인증 경로를 막지 않는다. 다음 주기에 다시 시도한다.
            long now = System.nanoTime();
            long previous = lastFailureLogNanos.get();
            if ((previous == 0 || now - previous >= FAILURE_LOG_INTERVAL_NANOS)
                    && lastFailureLogNanos.compareAndSet(previous, now)) {
                LOGGER.warn("Auth throttling counter purge failed; failureType={}",
                        exception.getClass().getSimpleName());
            }
        }
    }
}
