package com.atlas.inventory.dto.response;

import java.time.Instant;
import java.util.UUID;

public record InventoryAdjustmentResponse(UUID sellerId, String skuId, String idempotencyKey,
        int delta, String reason, InventoryPoolResponse pool, Instant createdAt) {}
