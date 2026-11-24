package com.atlas.inventory.persistence.projection;

import java.time.Instant;
import java.util.UUID;

public record InventoryTransferRecord(
        UUID transferId,
        UUID sellerId,
        String skuId,
        UUID sourceLocationId,
        UUID destinationLocationId,
        String idempotencyKey,
        int quantity,
        String reason,
        Instant createdAt) {
}
