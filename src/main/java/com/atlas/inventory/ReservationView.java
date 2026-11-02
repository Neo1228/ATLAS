package com.atlas.inventory;

import java.util.UUID;

public record ReservationView(UUID orderId, UUID campaignId, String skuId, int quantity, ReservationStatus status) {
}
