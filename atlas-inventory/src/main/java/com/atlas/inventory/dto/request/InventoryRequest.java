package com.atlas.inventory.dto.request;

import java.util.List;
import java.util.UUID;

public final class InventoryRequest {
    private InventoryRequest() {
    }

    public record AdjustInventoryCapacity(UUID sellerId, String skuId, String idempotencyKey, int delta,
            String reason) {}

    public record CreateFulfillmentLocation(UUID sellerId, String code, String name, int priority) {}

    public record CreateInventoryPool(UUID sellerId, String skuId, int initialStock) {
    }

    public record ReserveInventoryBatch(UUID orderId, UUID campaignId, List<Item> items) {
        public record Item(UUID sellerId, String skuId, int quantity) {
        }
    }

    public record ReserveInventory(UUID orderId, UUID campaignId, UUID sellerId, String skuId, int quantity) {
    }

    public record TransferInventory(UUID sellerId, String skuId, UUID sourceLocationId,
            UUID destinationLocationId, int quantity, String idempotencyKey, String reason) {}

    public record UpdateFulfillmentLocation(UUID sellerId, UUID locationId, String name, int priority, boolean active) {}
}
