package com.atlas.settlement.dto.request;

import java.util.List;
import java.util.UUID;

public final class SettlementPostingRequest {
    private SettlementPostingRequest() {
    }

    public record Capture(UUID orderId, UUID attemptId, UUID sellerId, long grossAmountWon) {
    }

    public record CheckoutSettlement(UUID checkoutId, UUID attemptId, long totalAmountWon,
                                                   List<SellerOrderAllocation> allocations) {
    }

    public record Refund(UUID orderId, UUID refundId, UUID sellerId, long amountWon) {
    }

    public record SellerOrderAllocation(UUID orderId, UUID sellerId, long amountWon) {
    }
}
