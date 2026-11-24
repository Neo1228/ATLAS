package com.atlas.payment.model;

/** 웹훅 재시도 시도 한 건의 결과. 값 집합은 {@code paypal_webhook_retry_attempt_history_outcome_check} 를 따른다. */
public enum WebhookRetryAttemptOutcome {
    CLAIMED,
    CORRELATION_MISS,
    TRANSIENT_ERROR,
    PROCESSED,
    PERMANENT_REJECT,
    EXHAUSTED,
    LEASE_EXPIRED
}
