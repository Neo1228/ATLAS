package com.atlas.application.controller.dto;

import com.atlas.inventory.dto.response.InventoryPoolResponse;
import java.util.UUID;

public record InventoryPoolHttpResponse(UUID sellerId, String skuId, long initialCapacity,
        long available, long reserved, long claimed) {
    public static InventoryPoolHttpResponse from(InventoryPoolResponse response) {
        return new InventoryPoolHttpResponse(response.sellerId(), response.skuId(), response.initialCapacity(),
                response.available(), response.reserved(), response.claimed());
    }
}
