package com.atlas.inventory.dto.request;

import java.util.UUID;

public record TransferInventoryRequest(UUID sellerId, String skuId, UUID sourceLocationId,
        UUID destinationLocationId, int quantity, String idempotencyKey, String reason) {}
