package com.atlas.settlement.persistence.state;

/**
 * 지급 실행 상태.
 *
 * <p>REQUESTED/APPROVED 는 외부 전송 전 상태다. DISPATCHING/SENT/UNKNOWN/UNCLAIMED/NEEDS_REVIEW 는
 * provider 전송 또는 결과 조정 중이며 payable 예약을 유지한다. CANCELLED/FAILED 는 provider 전
 * 취소 또는 미지급이 확정된 terminal 상태다. CONFIRMED 는 provider item 성공 후 ledger journal 이
 * 기록된 terminal 상태다.
 */
public enum SettlementPayoutState {
    REQUESTED,
    APPROVED,
    DISPATCHING,
    SENT,
    UNKNOWN,
    UNCLAIMED,
    NEEDS_REVIEW,
    CANCELLED,
    CONFIRMED,
    FAILED
}
