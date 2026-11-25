package com.atlas.admission.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class AdmissionLimitsTest {
    private final AdmissionLimits limits = new AdmissionLimits(20, 100, Duration.ofMinutes(5), Duration.ofHours(2), 1);

    @Test
    void rejectsDefaultsOutsideTheirLimits() {
        assertThrows(IllegalArgumentException.class,
                () -> new AdmissionLimits(0, 100, Duration.ofMinutes(5), Duration.ofHours(2), 1));
        assertThrows(IllegalArgumentException.class,
                () -> new AdmissionLimits(101, 100, Duration.ofMinutes(5), Duration.ofHours(2), 1));
        assertThrows(IllegalArgumentException.class,
                () -> new AdmissionLimits(20, 100, Duration.ZERO, Duration.ofHours(2), 1));
        assertThrows(IllegalArgumentException.class,
                () -> new AdmissionLimits(20, 100, Duration.ofHours(3), Duration.ofHours(2), 1));
    }

    @Test
    void resolvesOmittedValuesToDefaultsAndKeepsExplicitValues() {
        assertEquals(20, limits.resolveMaxActiveCheckouts(null));
        assertEquals(7, limits.resolveMaxActiveCheckouts(7));
        assertEquals(Duration.ofMinutes(5), limits.resolveTicketTtl(null));
        assertEquals(Duration.ofSeconds(90), limits.resolveTicketTtl(Duration.ofSeconds(90)));
    }

    @Test
    void acceptsValuesUpToTheLimitAndRejectsBeyondIt() {
        assertTrue(limits.allowsMaxActiveCheckouts(1));
        assertTrue(limits.allowsMaxActiveCheckouts(100));
        assertFalse(limits.allowsMaxActiveCheckouts(0));
        assertFalse(limits.allowsMaxActiveCheckouts(101));
        assertTrue(limits.allowsTicketTtl(Duration.ofSeconds(1)));
        assertTrue(limits.allowsTicketTtl(Duration.ofHours(2)));
        assertFalse(limits.allowsTicketTtl(Duration.ofMillis(999)));
        assertFalse(limits.allowsTicketTtl(Duration.ofHours(2).plusSeconds(1)));
        assertFalse(limits.allowsTicketTtl(null));
    }
}
