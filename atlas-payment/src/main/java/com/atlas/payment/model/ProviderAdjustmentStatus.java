package com.atlas.payment.model;

public enum ProviderAdjustmentStatus {
    /** 금액이 정해졌고 판매자 원장 차감을 기다린다. */
    POSTING,
    /** 환불 분개(settlement_journal.source_refund_id = adjustment id)를 남겼다. */
    POSTED,
    /** 자동으로 돈을 움직이지 않고 운영자 검토를 기다린다. */
    REVIEW,
    /** 진행 중인 분쟁. PayPal 이 분쟁 금액을 묶어 두므로 판매자 송금도 그 금액만큼 보류한다(ADR-0115). */
    HOLD,
    /** 판매자 차감 없이 끝난 분쟁(판매자 승소, PayPal 보호, 구매자 취소, 이미 역전으로 차감됨). */
    CLOSED
}
