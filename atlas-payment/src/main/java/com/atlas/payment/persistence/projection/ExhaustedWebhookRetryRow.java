package com.atlas.payment.persistence.projection;

import java.time.Instant;

public record ExhaustedWebhookRetryRow(
        String eventId, String eventType, int attemptCount, Instant createdAt, Instant finishedAt) { }
