package com.atlas.payment.persistence.projection;

import com.atlas.payment.model.WebhookRetryLeaseState;
import java.time.Instant;

public record PendingWebhookRetry(String eventId, String eventType, int attemptCount, Instant nextAttemptAt,
        WebhookRetryLeaseState leaseState) {
}
