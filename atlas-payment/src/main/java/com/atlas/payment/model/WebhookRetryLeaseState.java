package com.atlas.payment.model;

/**
 * 대기 중인 웹훅 재시도의 lease 상태.
 *
 * <p>저장 컬럼이 아니라 {@code PayPalWebhookRetryHistoryRepository.listPending} 이 {@code lease_owner} 와
 * {@code lease_until} 로 계산하는 값이다.
 */
public enum WebhookRetryLeaseState {
    UNLEASED,
    EXPIRED,
    LEASED
}
