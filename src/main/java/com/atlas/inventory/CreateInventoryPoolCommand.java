package com.atlas.inventory;

import java.util.UUID;

public record CreateInventoryPoolCommand(UUID campaignId, String skuId, int capacity) {
}
