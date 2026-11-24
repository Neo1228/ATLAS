package com.atlas.settlement.dto.request;

import java.time.Instant;
import java.util.UUID;

public record SellerSettlementStatementRequest(UUID sellerId, Instant from, Instant to, int limit, String cursor) {
}
