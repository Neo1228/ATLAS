package com.atlas.application.dto.response;

import java.time.Instant;
import java.util.List;

public record PayPalWebhookExhaustedRetryPage(
        List<Item> content,
        int page,
        int size,
        long totalElements,
        int totalPages) {

    public PayPalWebhookExhaustedRetryPage {
        content = List.copyOf(content);
    }

    public record Item(String eventId, String eventType, int attemptCount, Instant createdAt, Instant finishedAt) { }
}
