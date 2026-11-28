package com.atlas.payment.model;

public enum ProviderAdjustmentStatus {
    /** 금액이 정해졌고 판매자 원장 차감을 기다린다. */
    POSTING,
    /** 환불 분개(settlement_journal.source_refund_id = adjustment id)를 남겼다. */
    POSTED,
    /** 자동으로 돈을 움직이지 않고 운영자 검토를 기다린다. */
    REVIEW
}
