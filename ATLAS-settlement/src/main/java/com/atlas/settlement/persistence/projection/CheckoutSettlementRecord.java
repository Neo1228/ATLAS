package com.atlas.settlement.persistence.projection;

import java.util.List;
import java.util.UUID;

public record CheckoutSettlementRecord(UUID checkoutId, UUID attemptId, long totalAmountWon,
                                       List<CheckoutSettlementAllocation> allocations) {
}
