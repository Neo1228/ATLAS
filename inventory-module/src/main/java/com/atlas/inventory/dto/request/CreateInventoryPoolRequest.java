package com.atlas.inventory.dto.request;

import java.util.UUID;

public record CreateInventoryPoolRequest(UUID campaignId, String skuId, int capacity) {
}
