package com.atlas.inventory.dto.response;

import com.atlas.inventory.model.ReservationStatus;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class InventoryResponse {
    private InventoryResponse() {
    }

    public record Adjustment(UUID sellerId, String skuId, String idempotencyKey,
            int delta, String reason, Pool pool, Instant createdAt) {}

    public record Location(UUID locationId, UUID sellerId, String code, String name,
            int priority, boolean active) {}

    public record Pool(
            UUID sellerId,
            String skuId,
            long initialCapacity,
            long available,
            long reserved,
            long claimed) {
    }

    public record Transfer(UUID transferId, UUID sellerId, String skuId,
            UUID sourceLocationId, UUID destinationLocationId, String idempotencyKey, int quantity,
            String reason, Instant createdAt) {}

    /** 위치별 재고 한 페이지. {@code nextCursor}가 null이면 마지막 페이지다. */
    public record LocationInventoryPage(List<LocationInventory> items, String nextCursor) {
        public LocationInventoryPage {
            items = List.copyOf(items);
        }
    }

    public record LocationInventory(UUID sellerId, UUID locationId, String skuId,
            int initialCapacity, int available, int reserved, int claimed) {}

    public record Reservation(UUID orderId, UUID campaignId, UUID sellerId, String skuId,
            int quantity, ReservationStatus status, UUID locationId) {
        public Reservation(UUID orderId, UUID campaignId, UUID sellerId, String skuId,
                int quantity, ReservationStatus status) {
            this(orderId, campaignId, sellerId, skuId, quantity, status, null);
        }
    }
}
