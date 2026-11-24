package com.atlas.settlement.persistence.projection;

import com.atlas.settlement.model.SettlementStatementEntryType;
import java.time.Instant;
import java.util.UUID;

public record SellerStatementRow(UUID journalId, UUID orderId, UUID sourceId, SettlementStatementEntryType type, Instant recordedAt,
                                 long signedAmountWon) {
}
