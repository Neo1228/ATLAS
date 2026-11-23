package com.atlas.inventory.location.dto.response;

import java.util.UUID;

public record InventoryStockResponse(
        UUID fulfillmentLocationId,
        UUID sellerId,
        String skuId,
        long initialCapacity,
        long available,
        long reserved,
        long claimed) {
}
