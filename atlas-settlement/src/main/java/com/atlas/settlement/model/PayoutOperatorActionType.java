package com.atlas.settlement.model;

/**
 * 지급 운영자 조치 감사 이력({@code settlement_payout_operator_action.action})에 남기는 조치 종류.
 *
 * <p>테이블에 action CHECK 제약이 없어 값 집합은 이 조치를 기록하는 코드 전체에서 모았다.
 * PAYOUT_* 는 PayPal outbox 경로의 승인·재처리·취소, MANUAL_* 는 provider outbox 없는 수동 지급,
 * PROVIDER_REVIEW_* 는 provider 결과 확인 대기 건을 운영자가 판정한 결과다.
 */
public enum PayoutOperatorActionType {
    PAYOUT_APPROVED,
    PAYOUT_APPROVAL_REPLAYED,
    PAYOUT_CANCELLED,
    PAYOUT_REQUEUED,
    MANUAL_PAYOUT_APPROVED,
    MANUAL_PAYOUT_SENT,
    MANUAL_PAYOUT_CONFIRMED,
    MANUAL_PAYOUT_FAILED,
    MANUAL_NO_TRANSFER_CONFIRMED,
    PROVIDER_REVIEW_PAID,
    PROVIDER_REVIEW_NOT_PAID
}
