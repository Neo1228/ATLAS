package com.atlas.settlement.dto.response;

/** 판매자 지급 현황. 금액 의미를 응답에서 바로 읽을 수 있게 나눈다. */
public record PayoutSummaryResponse(long payableAmountWon, long availablePayoutAmountWon,
        long reserveAmountWon, long feeAmountWon, long pendingPayoutAmountWon,
        long pendingRefundAmountWon, long confirmedPayoutAmountWon) {
}
