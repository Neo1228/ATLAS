package com.atlas.payment.model;

/** PayPal 쪽에서 시작돼 우리 요청과 짝이 없는 사건의 종류(ADR-0111). */
public enum ProviderAdjustmentKind {
    /** 대시보드 등에서 판매자 계정이 직접 낸 환불. PAYMENT.CAPTURE.REFUNDED / PAYMENT.REFUND.COMPLETED. */
    REFUND,
    /** PayPal 이 capture 를 되돌림. PAYMENT.CAPTURE.REVERSED. */
    REVERSAL,
    /** capture 거절. PAYMENT.CAPTURE.DENIED. 돈을 움직이지 않고 검토로 보낸다. */
    DENIAL,
    /** 구매자 분쟁. CUSTOMER.DISPUTE.*. 진행 중에는 송금을 보류하고, 판매자 패소로 끝나면 돌려준 금액을 차감한다(ADR-0115). */
    DISPUTE;

    public boolean movesMoney() {
        return this == REFUND || this == REVERSAL;
    }
}
