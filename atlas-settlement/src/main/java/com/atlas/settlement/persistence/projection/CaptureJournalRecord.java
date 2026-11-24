package com.atlas.settlement.persistence.projection;

import java.util.UUID;

public record CaptureJournalRecord(UUID orderId, UUID attemptId, UUID sellerId, long grossAmountWon) {
}
