package com.atlas.payment.persistence.projection;

import java.time.Instant;

public record WebhookRetryTimelineEntry(String category, Instant recordedAt, Integer attemptNumber) {
}
