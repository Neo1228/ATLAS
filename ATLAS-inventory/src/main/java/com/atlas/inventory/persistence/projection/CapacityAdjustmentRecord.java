package com.atlas.inventory.persistence.projection;

import java.time.Instant;
import java.util.UUID;

public record CapacityAdjustmentRecord(UUID sellerId, String skuId, String idempotencyKey, int delta,
                                       String reason, Instant createdAt, InventoryPoolState pool) {}
