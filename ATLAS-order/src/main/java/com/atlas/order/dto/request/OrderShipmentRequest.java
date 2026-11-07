package com.atlas.order.dto.request;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

public record OrderShipmentRequest(
        UUID sellerId,
        UUID orderId,
        String idempotencyKey,
        String carrierCode,
        String trackingNumber,
        List<Item> items) {
    public OrderShipmentRequest {
        items = items == null ? null : Collections.unmodifiableList(new ArrayList<>(items));
    }

    public record Item(UUID orderItemId, int quantity) { }
}
