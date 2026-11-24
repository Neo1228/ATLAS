package com.atlas.inventory.dto.response;

import java.util.UUID;

public record LocationInventoryResponse(UUID sellerId, UUID locationId, String skuId,
        int initialCapacity, int available, int reserved, int claimed) {}
