package com.atlas.settlement.dto.response;

import java.math.BigInteger;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record SellerSettlementStatementResponse(
        UUID sellerId,
        Instant from,
        Instant to,
        Summary totals,
        List<Entry> items,
        String nextCursor) {

    public record Summary(
            BigInteger capturedAmountWon,
            BigInteger refundReversalAmountWon,
            BigInteger netLedgerChangeWon) {
    }

    public record Entry(UUID journalId, UUID orderId, UUID sourceId, EntryType type, Instant recordedAt,
                        long signedAmountWon) {
    }

    public enum EntryType {
        ORDER_CAPTURE,
        CHECKOUT_ALLOCATION,
        REFUND_REVERSAL
    }
}
