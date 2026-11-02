package com.atlas.inventory;

import java.util.UUID;

public record ReserveInventoryCommand(UUID orderId, UUID campaignId, String skuId, int quantity) {
}
