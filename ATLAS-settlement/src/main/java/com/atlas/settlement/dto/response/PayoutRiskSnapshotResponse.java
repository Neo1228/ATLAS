package com.atlas.settlement.dto.response;

public record PayoutRiskSnapshotResponse(long negativePayableSellerCount, long negativePayableAmountWon,
        long pendingRefundReservationCount, long pendingRefundReservationAmountWon) {
}
