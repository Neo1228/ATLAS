package com.atlas.payment.model;

/** 웹훅 재시도가 다시 대기열에 오르는 이유. */
public enum WebhookRetryFailure {
    CORRELATION_MISS,
    TRANSIENT_ERROR;

    /** 재시도 이력에 남는 결과. 시도 한도에 닿으면 저장소가 {@link WebhookRetryAttemptOutcome#EXHAUSTED} 로 바꿔 남긴다. */
    public WebhookRetryAttemptOutcome attemptOutcome() {
        return switch (this) {
            case CORRELATION_MISS -> WebhookRetryAttemptOutcome.CORRELATION_MISS;
            case TRANSIENT_ERROR -> WebhookRetryAttemptOutcome.TRANSIENT_ERROR;
        };
    }
}
