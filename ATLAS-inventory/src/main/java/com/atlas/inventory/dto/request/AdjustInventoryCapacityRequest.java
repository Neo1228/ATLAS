package com.atlas.inventory.dto.request;

import java.util.UUID;

public record AdjustInventoryCapacityRequest(UUID sellerId, String skuId, String idempotencyKey, int delta,
        String reason) {}
