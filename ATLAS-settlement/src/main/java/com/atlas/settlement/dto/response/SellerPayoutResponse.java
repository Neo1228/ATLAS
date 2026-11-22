package com.atlas.settlement.dto.response;

import java.time.Instant;
import java.util.UUID;

/** 판매자 지급 실행 결과. */
public record SellerPayoutResponse(
        UUID payoutId,
        UUID sellerId,
        Instant periodStart,
        Instant periodEnd,
        long amountWon,
        long settledAmountWon,
        SettlementPayoutStatus state,
        Instant requestedAt,
        Instant sentAt,
        Instant confirmedAt,
        Instant cancelledAt) {
}
