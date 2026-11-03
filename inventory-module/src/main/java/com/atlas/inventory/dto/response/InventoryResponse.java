package com.atlas.inventory.dto.response;

import java.util.UUID;

public record InventoryResponse(
        UUID sellerId,
        String skuId,
        int initialCapacity,
        int available,
        int reserved,
        int claimed) {
}
