package com.atlas.settlement.dto.request;

import java.util.UUID;

public record RefundPostingRequest(UUID orderId, UUID refundId, UUID sellerId, long amountWon) {
}
