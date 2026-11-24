package com.atlas.inventorylocation.dto.response;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record InventoryReservationResponse(
        UUID reservationId,
        UUID fulfillmentLocationId,
        UUID orderId,
        String idempotencyKey,
        String status,
        List<Item> items,
        Instant createdAt,
        Instant expiresAt,
        boolean replayed) {
    public record Item(UUID sellerId, String skuId, int quantity) {
    }
}
