package com.atlas.settlement.dto.request;

import java.util.List;
import java.util.UUID;

public record CheckoutSettlementPostingRequest(UUID checkoutId, UUID attemptId, long totalAmountWon,
                                               List<SellerOrderAllocationRequest> allocations) {
}
