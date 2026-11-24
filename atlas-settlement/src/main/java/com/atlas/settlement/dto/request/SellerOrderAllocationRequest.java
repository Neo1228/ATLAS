package com.atlas.settlement.dto.request;

import java.util.UUID;

public record SellerOrderAllocationRequest(UUID orderId, UUID sellerId, long amountWon) {
}
