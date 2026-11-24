package com.atlas.payment.service;

import com.atlas.payment.dto.response.WebhookRetryResponse;

/** 재시도를 소진한 PayPal 웹훅 이벤트를 운영자가 조회하고 다시 대기열에 올린다. */
public interface PayPalWebhookOperatorService {
    int DEFAULT_PAGE_SIZE = 20;
    int MAX_PAGE_SIZE = 100;
    int MAX_PAGE_OFFSET = 10_000;
    int MAX_MANUAL_REQUEUES = 3;

    WebhookRetryResponse.ExhaustedRetryPage listExhausted(int page, int requestedSize);

    WebhookRetryResponse.Requeue requeue(String eventId, String idempotencyKey, String actor, String reason);
}
