package com.atlas.inventory.dto.response;

import java.time.Instant;
import java.util.UUID;

public record InventoryTransferResponse(UUID transferId, UUID sellerId, String skuId,
        UUID sourceLocationId, UUID destinationLocationId, String idempotencyKey, int quantity,
        String reason, Instant createdAt) {}
