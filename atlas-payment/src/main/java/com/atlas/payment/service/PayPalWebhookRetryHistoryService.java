package com.atlas.payment.service;

import com.atlas.payment.dto.response.WebhookRetryResponse;

/** PayPal 웹훅 재시도 대기열의 요약과 시도 이력을 읽는다. */
public interface PayPalWebhookRetryHistoryService {
    int DEFAULT_PAGE_SIZE = 20;
    int MAX_PAGE_SIZE = 100;
    int MAX_PAGE_OFFSET = 10_000;

    WebhookRetryResponse.RetrySummary summary();

    WebhookRetryResponse.PendingRetryPage pending(int page, int size);

    WebhookRetryResponse.RetryAttemptHistory history(String eventId, int page, int size);
}
