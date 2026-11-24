package com.atlas.payment.persistence.projection;

import com.atlas.payment.model.WebhookRetryAttemptOutcome;
import java.time.Instant;
import java.util.Map;

public record WebhookRetrySummary(long pending, long exhausted, Instant oldestPendingSince,
        Instant oldestExhaustedSince, long expiredLeaseCount, Map<WebhookRetryAttemptOutcome, Long> last24HoursOutcomeCounts) {
}
