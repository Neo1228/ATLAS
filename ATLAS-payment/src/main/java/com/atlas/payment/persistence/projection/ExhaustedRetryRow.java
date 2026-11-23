package com.atlas.payment.persistence.projection;

import java.time.Instant;

public record ExhaustedRetryRow(
        String eventId, String eventType, int attemptCount, Instant createdAt, Instant finishedAt) { }
