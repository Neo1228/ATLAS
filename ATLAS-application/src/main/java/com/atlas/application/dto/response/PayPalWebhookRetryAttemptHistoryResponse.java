package com.atlas.application.dto.response;

import java.time.Instant;
import java.util.List;

public record PayPalWebhookRetryAttemptHistoryResponse(String eventId, long total, int page, int size,
        List<Entry> entries) {
    public PayPalWebhookRetryAttemptHistoryResponse {
        entries = List.copyOf(entries);
    }

    // 수동 감사 항목은 분류와 시각만 포함하며 attemptNumber는 null입니다.
    public record Entry(String category, Instant recordedAt, Integer attemptNumber) { }
}
