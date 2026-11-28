package com.atlas.payment.persistence.projection;

/**
 * 한 결제 시도의 capture에서 이미 빠졌거나 빠질 금액의 합: 실패하지 않은 우리 환불(payment_refund_operation)과
 * 원장 차감 대상인 provider 발 환불·역전(payment_provider_adjustment, POSTING·POSTED).
 */
public record RefundedTotals(long providerAmountMinor, long amountWon) {
}
