package com.atlas.payment.persistence.projection;

import java.time.Instant;
import java.util.Map;

public record RetrySummary(long pending, long exhausted, Instant oldestPendingSince,
        Instant oldestExhaustedSince, long expiredLeaseCount, Map<String, Long> last24HoursOutcomeCounts) {
}
