package com.atlas.inventory.dto.request;

import java.util.UUID;

public record CreateInventoryPoolRequest(UUID sellerId, String skuId, int initialStock) {
}
