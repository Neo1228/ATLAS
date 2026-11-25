package com.atlas.payment.model;

/**
 * 운영자 수동 재큐 감사 기록의 결과. 값 집합은 {@code paypal_webhook_retry_operator_audit_outcome_check} 를 따른다.
 *
 * <p>값이 하나뿐인 것은 의도다. 실패한 재큐는 예외로 거절되고 감사 행을 남기지 않는다. 그래도 응답 JSON의
 * {@code outcome} 필드와 감사 컬럼은 이미 노출된 형태다. 제거하면 외부 클라이언트 영향과 V3 마이그레이션 비용이 생기는데 얻는 것이 없어 유지한다(D-143).
 */
public enum WebhookRequeueOutcome {
    REQUEUED
}
