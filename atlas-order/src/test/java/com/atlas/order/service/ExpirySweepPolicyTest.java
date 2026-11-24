package com.atlas.order.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class ExpirySweepPolicyTest {

    @Test
    void rejectsSweepSizesOutsideTheSupportedRange() {
        assertThrows(IllegalArgumentException.class, () -> new ExpirySweepPolicy(0, Duration.ofSeconds(1)));
        assertThrows(IllegalArgumentException.class, () -> new ExpirySweepPolicy(1_001, Duration.ofSeconds(1)));
        assertEquals(1, new ExpirySweepPolicy(1, Duration.ofSeconds(1)).batchSize());
        assertEquals(1_000, new ExpirySweepPolicy(1_000, Duration.ofSeconds(1)).batchSize());
    }

    @Test
    void rejectsNonPositiveTimeBudgets() {
        assertThrows(IllegalArgumentException.class, () -> new ExpirySweepPolicy(10, Duration.ZERO));
        assertThrows(IllegalArgumentException.class, () -> new ExpirySweepPolicy(10, Duration.ofSeconds(-1)));
        assertThrows(IllegalArgumentException.class, () -> new ExpirySweepPolicy(10, null));
    }

    @Test
    void defaultsStayWithinTheSchedulerBudget() {
        ExpirySweepPolicy policy = ExpirySweepPolicy.defaults();

        assertEquals(100, policy.batchSize());
        assertEquals(Duration.ofSeconds(2), policy.timeBudget());
    }
}
