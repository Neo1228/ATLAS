package com.atlas.settlement.persistence.projection;

import java.time.Instant;
import java.util.UUID;

public record RefundReservation(UUID refundId, UUID orderId, UUID sellerId,
        long amountWon, String status, Instant createdAt, Instant updatedAt) { }
