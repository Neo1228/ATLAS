package com.atlas.admission.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class AdmissionExpirySweepPolicyTest {
    @Test
    void rejectsInvalidBatchSizes() {
        assertThrows(IllegalArgumentException.class, () -> new AdmissionExpirySweepPolicy(0, Duration.ofSeconds(1)));
        assertThrows(IllegalArgumentException.class, () -> new AdmissionExpirySweepPolicy(1_001, Duration.ofSeconds(1)));
        assertEquals(1, new AdmissionExpirySweepPolicy(1, Duration.ofSeconds(1)).batchSize());
    }

    @Test
    void rejectsNonPositiveTimeBudgets() {
        assertThrows(IllegalArgumentException.class, () -> new AdmissionExpirySweepPolicy(10, Duration.ZERO));
        assertThrows(IllegalArgumentException.class, () -> new AdmissionExpirySweepPolicy(10, Duration.ofSeconds(-1)));
        assertThrows(IllegalArgumentException.class, () -> new AdmissionExpirySweepPolicy(10, null));
    }

    @Test
    void providesConservativeDefaults() {
        assertEquals(100, AdmissionExpirySweepPolicy.DEFAULT_BATCH_SIZE);
        assertEquals(Duration.ofSeconds(2), AdmissionExpirySweepPolicy.DEFAULT_TIME_BUDGET);
    }
}
