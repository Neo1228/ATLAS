package com.atlas.order.model;

/** 권고의 근거 코드. 줄마다 하나, 반품 전체에는 줄 근거의 합집합과 금액 근거가 붙는다(ADR-0110). */
public enum ReturnRecommendationReason {
    /** 미개봉 또는 개봉만 한 상태라 사유와 무관하게 반품을 받는다. */
    RESELLABLE_CONDITION,
    /** 판매자가 하자·오배송·설명 불일치를 확인했다. */
    DEFECT_CONFIRMED,
    /** 단순 변심인데 사용 흔적이나 파손이 있다. */
    BUYER_CAUSED_WEAR,
    /** 판매자 책임 주장 또는 사유 없음인데 사용 흔적이나 파손이 있어 원인을 사람이 판단해야 한다. */
    CLAIM_NEEDS_JUDGMENT,
    /** 반품 상자에 상품이 없다. */
    ITEM_NOT_RETURNED,
    /** 반품 요청 시각이 그 줄 출고의 반품 기간 끝보다 늦다. */
    OUTSIDE_RETURN_WINDOW,
    /** 반품 기간 밖이지만 판매자가 하자를 확인해 사람이 판단해야 한다. */
    OUTSIDE_WINDOW_DEFECT,
    /** 출고 시각을 알 수 없어 반품 기간을 판정할 수 없다. */
    DELIVERY_TIME_UNKNOWN,
    /** 이미 환불된 수량과 합치면 주문 수량을 넘는다. 반품 전체를 거절한다. */
    DUPLICATE_REFUND,
    /** 승인 권고의 환불 합계가 자동 권고 상한을 넘는다. */
    AMOUNT_ABOVE_REVIEW_LIMIT
}
