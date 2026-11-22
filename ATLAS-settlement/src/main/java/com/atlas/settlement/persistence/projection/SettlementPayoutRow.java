package com.atlas.settlement.persistence.projection;

import com.atlas.settlement.persistence.state.SettlementPayoutState;
import java.time.Instant;
import java.util.UUID;

/** 지급 실행 row. repository 는 이 projection 만 알고, API DTO 변환은 service 가 한다. */
public record SettlementPayoutRow(
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
