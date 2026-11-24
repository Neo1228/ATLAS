package com.atlas.settlement.model;

/**
 * 환불 예약의 상태.
 *
 * <p>값 집합은 {@code settlement_refund_reservation_status_check} 제약과 같다.
 * RESERVED 는 환불 금액을 정산 잔액에서 떼어 둔 상태이고, POSTED 와 RELEASED 는 각각 ledger 에 기록되거나
 * 예약이 풀린 terminal 상태다.
 */
public enum RefundReservationState {
    RESERVED,
    POSTED,
    RELEASED
}
