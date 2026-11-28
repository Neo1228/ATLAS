package com.atlas.payment.persistence.projection;

/** 한 결제 시도에서 이미 원장 차감 대상으로 잡힌 provider 발 환불·역전의 합. */
public record ProviderAdjustmentTotals(long providerAmountMinor, long amountWon) {
}
