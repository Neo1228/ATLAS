package com.atlas.settlement.persistence.projection;

import java.util.UUID;

public record CheckoutSettlementAllocation(UUID orderId, UUID sellerId, long amountWon) {
}
