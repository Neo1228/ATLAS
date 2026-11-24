package com.atlas.inventory.persistence.projection;

import java.util.UUID;

public record InventoryPoolSummary(
        UUID sellerId,
        String skuId,
        long initialCapacity,
        long available,
        long reserved,
        long claimed) {
}
