package com.atlas.application.controller.dto;

import com.atlas.inventory.dto.response.InventoryAdjustmentResponse;
import java.time.Instant;
import java.util.UUID;

public record InventoryAdjustmentHttpResponse(UUID sellerId, String skuId, String idempotencyKey,
        int delta, String reason, InventoryPoolHttpResponse pool, Instant createdAt) {
    public static InventoryAdjustmentHttpResponse from(InventoryAdjustmentResponse response) {
        return new InventoryAdjustmentHttpResponse(response.sellerId(), response.skuId(), response.idempotencyKey(),
                response.delta(), response.reason(), InventoryPoolHttpResponse.from(response.pool()),
                response.createdAt());
    }
}
