package com.atlas.payment.model;

/** 운영자 수동 재큐 감사 기록의 결과. 값 집합은 {@code paypal_webhook_retry_operator_audit_outcome_check} 를 따른다. */
public enum WebhookRequeueOutcome {
    REQUEUED
}
