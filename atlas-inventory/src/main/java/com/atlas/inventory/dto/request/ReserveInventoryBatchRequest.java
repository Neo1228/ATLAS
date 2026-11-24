package com.atlas.inventory.dto.request;

import java.util.List;
import java.util.UUID;

public record ReserveInventoryBatchRequest(UUID orderId, UUID campaignId, List<Item> items) {
    public record Item(UUID sellerId, String skuId, int quantity) {
    }
}
