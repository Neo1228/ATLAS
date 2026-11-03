package com.atlas.inventory.dto.request;

import java.util.UUID;

public record ReserveInventoryRequest(UUID orderId, UUID campaignId, UUID sellerId, String skuId, int quantity) {
}
