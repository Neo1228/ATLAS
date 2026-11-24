package com.atlas.payment.model;

/** 웹훅 재시도가 다시 대기열에 오르는 이유. */
public enum WebhookRetryFailure {
    CORRELATION_MISS,
    TRANSIENT_ERROR
}
