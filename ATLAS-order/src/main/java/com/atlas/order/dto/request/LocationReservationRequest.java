package com.atlas.order.dto.request;

import java.util.List;
import java.util.UUID;

public record LocationReservationRequest(UUID orderId, List<LocationAllocation> allocations) {
    public record LocationAllocation(UUID fulfillmentLocationId, List<Item> items) {
    }

    public record Item(UUID sellerId, String skuId, int quantity) {
    }
}
