package com.atlas.application.controller.dto;

import com.atlas.inventory.dto.response.InventoryTransferResponse;
import java.time.Instant;
import java.util.UUID;

public record InventoryTransferHttpResponse(UUID transferId, UUID sellerId, String skuId,
        UUID sourceLocationId, UUID destinationLocationId, String idempotencyKey, int quantity,
        String reason, Instant createdAt) {
    public static InventoryTransferHttpResponse from(InventoryTransferResponse response) {
        return new InventoryTransferHttpResponse(response.transferId(), response.sellerId(), response.skuId(),
                response.sourceLocationId(), response.destinationLocationId(), response.idempotencyKey(),
                response.quantity(), response.reason(), response.createdAt());
    }
}
