package com.atlas.inventorylocation.dto.response;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class LocationResponse {
    private LocationResponse() {
    }

    /** 특정 Seller SKU의 location별 판매 가능 수량. 미등록 SKU는 0으로 반환한다. */
    public record Availability(UUID fulfillmentLocationId, UUID sellerId, String skuId, long available) {
    }

    public record Reservation(
            UUID reservationId,
            UUID fulfillmentLocationId,
            UUID orderId,
            String idempotencyKey,
            String status,
            List<Item> items,
            Instant createdAt,
            Instant expiresAt,
            boolean replayed) {
        public record Item(UUID sellerId, String skuId, int quantity) {
        }
    }

    public record Stock(
            UUID fulfillmentLocationId,
            UUID sellerId,
            String skuId,
            long initialCapacity,
            long available,
            long reserved,
            long claimed) {
    }
}
