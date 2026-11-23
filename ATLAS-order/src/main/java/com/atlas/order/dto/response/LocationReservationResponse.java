package com.atlas.order.dto.response;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record LocationReservationResponse(
        UUID workflowId,
        UUID orderId,
        String idempotencyKey,
        String status,
        List<LocationResult> locations,
        Instant createdAt,
        Instant updatedAt,
        boolean replayed) {
    public record LocationResult(
            UUID fulfillmentLocationId,
            String status,
            UUID reservationId,
            Instant expiresAt,
            String lastErrorCode,
            List<Item> items) {
    }

    public record Item(UUID sellerId, String skuId, int quantity) {
    }
}
