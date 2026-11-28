package com.atlas.order.model;

/**
 * 반품 권고. 어느 값도 돈을 움직이지 않는다. 판매자가 기존 검수 호출에 줄별 결과를 보내야 환불된다.
 * {@code APPROVE_FULL}은 모든 줄 통과, {@code APPROVE_PARTIAL}은 일부 줄 통과, {@code REJECT}는 모든 줄 불통과,
 * {@code HUMAN_REVIEW}는 규칙이 정하지 못한 줄이 있거나 금액이 자동 권고 상한을 넘은 경우다.
 */
public enum ReturnRecommendationDecision {
    APPROVE_FULL,
    APPROVE_PARTIAL,
    REJECT,
    HUMAN_REVIEW;

    public boolean approves() {
        return this == APPROVE_FULL || this == APPROVE_PARTIAL;
    }
}
