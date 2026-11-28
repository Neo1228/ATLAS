package com.atlas.order.model;

/**
 * 구매자가 반품 요청에 고르는 사유. {@code CHANGED_MIND}는 단순 변심이고 나머지 넷({@code DEFECTIVE},
 * {@code DAMAGED_IN_DELIVERY}, {@code WRONG_ITEM}, {@code NOT_AS_DESCRIBED})은 판매자 책임을 주장하는 사유다.
 * 주장일 뿐 사실이 아니므로 권고 규칙은 판매자의 검수 상태({@link ReturnItemCondition})로 확인한다(ADR-0110).
 */
public enum ReturnReason {
    CHANGED_MIND,
    DEFECTIVE,
    DAMAGED_IN_DELIVERY,
    WRONG_ITEM,
    NOT_AS_DESCRIBED,
    OTHER;

    public boolean claimsSellerFault() {
        return this == DEFECTIVE || this == DAMAGED_IN_DELIVERY || this == WRONG_ITEM || this == NOT_AS_DESCRIBED;
    }
}
