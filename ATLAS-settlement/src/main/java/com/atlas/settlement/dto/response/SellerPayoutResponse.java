package com.atlas.settlement.dto.response;

import com.atlas.settlement.persistence.state.SettlementPayoutState;
import java.time.Instant;
import java.util.UUID;

/** 판매자 지급 실행 결과. */
public record SellerPayoutResponse(
        UUID payoutId,
        UUID sellerId,
        Instant periodStart,
        Instant periodEnd,
        long amountWon,
        SettlementPayoutState state,
        Instant requestedAt,
        Instant sentAt,
        Instant confirmedAt) {
}
