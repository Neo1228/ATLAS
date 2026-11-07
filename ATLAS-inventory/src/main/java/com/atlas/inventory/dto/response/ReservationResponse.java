package com.atlas.inventory.dto.response;

import com.atlas.inventory.persistence.state.ReservationStatus;
import java.util.UUID;

public record ReservationResponse(UUID orderId, UUID campaignId, UUID sellerId, String skuId,
        int quantity, ReservationStatus status, UUID locationId) {
    public ReservationResponse(UUID orderId, UUID campaignId, UUID sellerId, String skuId,
            int quantity, ReservationStatus status) {
        this(orderId, campaignId, sellerId, skuId, quantity, status, null);
    }
}
