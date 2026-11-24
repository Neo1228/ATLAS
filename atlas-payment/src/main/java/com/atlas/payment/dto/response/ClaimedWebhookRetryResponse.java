package com.atlas.payment.dto.response;

/** 대기열에서 lease를 얻은 웹훅 재시도 한 건. */
public record ClaimedWebhookRetryResponse(PaymentWebhookEventResponse event, int attempt, String leaseOwner) {
}
