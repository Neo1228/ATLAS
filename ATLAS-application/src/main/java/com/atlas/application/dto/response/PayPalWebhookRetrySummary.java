package com.atlas.application.dto.response;

import java.time.Instant;
import java.util.Map;

public record PayPalWebhookRetrySummary(long pending, long exhausted,
        Instant oldestPendingSince, Instant oldestExhaustedSince, long expiredLeaseCount,
        Map<String, Long> last24HoursOutcomeCounts) {
    public PayPalWebhookRetrySummary {
        last24HoursOutcomeCounts = Map.copyOf(last24HoursOutcomeCounts);
    }
}
