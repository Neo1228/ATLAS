package com.atlas.payment.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import org.junit.jupiter.api.Test;

class PayPalWebhookRetryDelayTest {
    @Test
    void baseDelayDoublesPerAttemptAndStopsGrowing() {
        assertThat(PayPalWebhookRetryRepository.baseDelaySeconds(0)).isEqualTo(1);
        assertThat(PayPalWebhookRetryRepository.baseDelaySeconds(1)).isEqualTo(1);
        assertThat(PayPalWebhookRetryRepository.baseDelaySeconds(2)).isEqualTo(2);
        assertThat(PayPalWebhookRetryRepository.baseDelaySeconds(5)).isEqualTo(16);
        assertThat(PayPalWebhookRetryRepository.baseDelaySeconds(9)).isEqualTo(256);
        assertThat(PayPalWebhookRetryRepository.baseDelaySeconds(40)).isEqualTo(256);
    }

    @Test
    void jitterStaysBetweenHalfAndTheFullBaseAndNeverBelowOneSecond() {
        for (int base : new int[] {1, 2, 3, 16, 64, 256, 300}) {
            int lowest = PayPalWebhookRetryRepository.jitteredDelaySeconds(base, bound -> 0);
            int highest = PayPalWebhookRetryRepository.jitteredDelaySeconds(base, bound -> bound - 1);
            assertThat(lowest).as("base %d lowest", base).isEqualTo(Math.max(1, base / 2));
            assertThat(highest).as("base %d highest", base).isEqualTo(base);
        }
    }

    @Test
    void jitterSpreadsAFailureBurstOverSeveralRetryTimes() {
        Set<Integer> delays = new HashSet<>();
        for (int event = 0; event < 1_000; event++) {
            delays.add(PayPalWebhookRetryRepository.jitteredDelaySeconds(64,
                    ThreadLocalRandom.current()::nextInt));
        }

        assertThat(delays).hasSizeGreaterThan(10);
        assertThat(delays).allMatch(delay -> delay >= 32 && delay <= 64);
    }
}
