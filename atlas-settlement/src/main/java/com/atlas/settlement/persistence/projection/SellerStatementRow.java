package com.atlas.settlement.persistence.projection;

import java.time.Instant;
import java.util.UUID;

public record SellerStatementRow(UUID journalId, UUID orderId, UUID sourceId, String type, Instant recordedAt,
                                 long signedAmountWon) {
}
