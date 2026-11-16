package com.atlas.application.dto.response;

import java.time.Instant;
import java.util.List;

public record PayPalWebhookPendingRetryPage(long total, int page, int size, List<Item> items) {
    public PayPalWebhookPendingRetryPage {
        items = List.copyOf(items);
    }

    public record Item(String eventId, String eventType, int attemptCount,
            Instant nextAttemptAt, String leaseState) { }
}
