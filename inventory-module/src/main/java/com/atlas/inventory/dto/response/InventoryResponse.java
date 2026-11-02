package com.atlas.inventory.dto.response;

import java.util.UUID;

public record InventoryResponse(
        UUID campaignId,
        String skuId,
        int initialCapacity,
        int available,
        int reserved,
        int claimed) {
}
