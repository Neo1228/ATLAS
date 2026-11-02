package com.atlas.inventory.dto.request;

import java.util.UUID;

public record ReserveInventoryRequest(UUID orderId, UUID campaignId, String skuId, int quantity) {
}
