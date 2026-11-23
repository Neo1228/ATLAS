package com.atlas.application.persistence.projection;

import java.time.Instant;

public record PendingRetry(String eventId, String eventType, int attemptCount, Instant nextAttemptAt,
        String leaseState) {
}
