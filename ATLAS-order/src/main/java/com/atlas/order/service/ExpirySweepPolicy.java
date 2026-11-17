package com.atlas.order.service;

import java.time.Duration;

/**
 * 만료 sweep이 한 tick에서 처리할 작업량의 상한.
 *
 * <p>후보 조회 개수와 경과 시간 중 먼저 도달하는 조건에서 tick을 종료해, backlog가 커져도
 * 공유 scheduler thread를 무기한 점유하지 않도록 한다.
 */
public record ExpirySweepPolicy(int batchSize, Duration timeBudget) {
    public static final int DEFAULT_BATCH_SIZE = 100;
    public static final Duration DEFAULT_TIME_BUDGET = Duration.ofSeconds(2);

    public ExpirySweepPolicy {
        if (batchSize < 1 || batchSize > 1_000) {
            throw new IllegalArgumentException("expiry sweep batch size must be between 1 and 1000");
        }
        if (timeBudget == null || timeBudget.isNegative() || timeBudget.isZero()) {
            throw new IllegalArgumentException("expiry sweep time budget must be positive");
        }
    }

    public static ExpirySweepPolicy defaults() {
        return new ExpirySweepPolicy(DEFAULT_BATCH_SIZE, DEFAULT_TIME_BUDGET);
    }
}
