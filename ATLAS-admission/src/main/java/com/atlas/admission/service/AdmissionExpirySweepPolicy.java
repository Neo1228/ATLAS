package com.atlas.admission.service;

import java.time.Duration;

/** 한 admission expiry tick의 후보 수와 경과 시간을 제한한다. */
public record AdmissionExpirySweepPolicy(int batchSize, Duration timeBudget) {
    public static final int DEFAULT_BATCH_SIZE = 100;
    public static final Duration DEFAULT_TIME_BUDGET = Duration.ofSeconds(2);

    public AdmissionExpirySweepPolicy {
        if (batchSize < 1 || batchSize > 1_000) {
            throw new IllegalArgumentException("admission expiry batch size must be between 1 and 1000");
        }
        if (timeBudget == null || timeBudget.isNegative() || timeBudget.isZero()) {
            throw new IllegalArgumentException("admission expiry time budget must be positive");
        }
    }
    public int candidateWindowSize() {
        return Math.multiplyExact(batchSize, 2);
    }
}
