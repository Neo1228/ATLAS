package com.atlas.settlement.persistence.projection;

import com.atlas.settlement.model.RefundReservationState;
import java.time.Instant;
import java.util.UUID;

public record RefundReservation(UUID refundId, UUID orderId, UUID sellerId,
        long amountWon, RefundReservationState status, Instant createdAt, Instant updatedAt) { }
