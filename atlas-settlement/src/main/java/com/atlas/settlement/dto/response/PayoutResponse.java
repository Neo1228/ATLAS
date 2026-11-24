package com.atlas.settlement.dto.response;

import com.atlas.settlement.model.SettlementPayoutState;
import java.time.Instant;
import java.util.UUID;

public final class PayoutResponse {
    private PayoutResponse() {
    }

    public record RiskSnapshot(long negativePayableSellerCount, long negativePayableAmountWon,
            long pendingRefundReservationCount, long pendingRefundReservationAmountWon) {
    }

    /** 판매자 지급 현황. 금액 의미를 응답에서 바로 읽을 수 있게 나눈다. */
    public record Summary(long payableAmountWon, long availablePayoutAmountWon,
            long reserveAmountWon, long feeAmountWon, long pendingPayoutAmountWon,
            long pendingRefundAmountWon, long confirmedPayoutAmountWon) {
    }

    /** 판매자 지급 실행 결과. */
    public record SellerPayout(
            UUID payoutId,
            UUID sellerId,
            Instant periodStart,
            Instant periodEnd,
            long amountWon,
            long settledAmountWon,
            SettlementPayoutState state,
            Instant requestedAt,
            Instant sentAt,
            Instant confirmedAt,
            Instant cancelledAt) {
    }
}
