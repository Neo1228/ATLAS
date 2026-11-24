package com.atlas.settlement.model;

/**
 * 판매자 정산 명세의 행 종류.
 *
 * <p>값 집합은 {@code SettlementJournalRepository} 의 명세 조회가 만드는 {@code type} 리터럴과 같다.
 * 저장된 컬럼이 아니라 조회 SQL 이 journal 출처(주문 capture, checkout 배분, 환불, 지급)에 따라 붙이는 값이다.
 *
 * <p>{@code PAYOUT} 은 지급 확정 분개다. 지급은 판매자의 지급 가능 잔액을 줄이므로
 * signed amount 가 음수로 내려간다. capture/refund 와 달리 gross 를 늘리지 않는다.
 */
public enum SettlementStatementEntryType {
    ORDER_CAPTURE,
    CHECKOUT_ALLOCATION,
    REFUND_REVERSAL,
    PAYOUT
}
