package com.atlas.settlement.persistence.projection;

import java.util.UUID;

public record RefundJournalRecord(UUID orderId, UUID refundId, UUID sellerId, long amountWon) {
}
