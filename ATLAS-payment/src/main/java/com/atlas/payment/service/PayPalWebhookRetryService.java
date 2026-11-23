package com.atlas.payment.service;

import com.atlas.payment.dto.response.PaymentWebhookEventResponse;
import com.atlas.payment.dto.response.WebhookRetryPollResponse;
import com.atlas.payment.persistence.state.WebhookRetryFailure;

/** 검증된 PayPal 웹훅의 재시도 대기열. 저장한 뒤 worker가 lease를 얻어 처리하고 결과를 기록한다. */
public interface PayPalWebhookRetryService {
    void enqueue(PaymentWebhookEventResponse event);

    WebhookRetryPollResponse pollDue();

    boolean complete(String eventId, String leaseOwner);

    boolean reschedule(String eventId, int attempt, String leaseOwner, WebhookRetryFailure failure, String reason);

    boolean reject(String eventId, int attempt, String leaseOwner, String reason);

    Double oldestPendingAgeSeconds();
}
