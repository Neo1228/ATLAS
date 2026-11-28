package com.atlas.order.model;

/**
 * 판매자가 수령한 반품 줄을 열어 보고 적는 상태. 권고 요청에만 쓰고 저장하지 않는다. 확정은 기존 검수
 * 결과({@link OrderReturnInspectionResult})이며 그 호출만 환불을 실행한다(ADR-0110).
 */
public enum ReturnItemCondition {
    /** 포장을 뜯지 않았다. */
    UNOPENED,
    /** 포장을 열었지만 사용 흔적이 없다. */
    OPENED,
    /** 사용 흔적이 있다. */
    USED,
    /** 파손됐다. 누가 파손했는지는 이 값만으로 알 수 없다. */
    DAMAGED,
    /** 판매자가 하자·오배송·설명 불일치를 직접 확인했다. */
    DEFECT_CONFIRMED,
    /** 반품 상자에 그 줄의 상품이 없다. */
    NOT_RETURNED
}
