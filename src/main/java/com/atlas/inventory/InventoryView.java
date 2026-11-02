package com.atlas.inventory;

import java.util.UUID;

public record InventoryView(
        UUID campaignId,
        String skuId,
        int initialCapacity,
        int available,
        int reserved,
        int claimed) {
}
