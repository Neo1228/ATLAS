package com.atlas.inventory.dto.response;

import java.util.UUID;

public record InventoryPoolResponse(
        UUID sellerId,
        String skuId,
        long initialCapacity,
        long available,
        long reserved,
        long claimed) {
}
