package com.atlas.order.dto.response;

import com.atlas.order.persistence.state.OrderShipmentStatus;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record OrderShipmentResponse(
        UUID shipmentId,
        UUID orderId,
        String idempotencyKey,
        OrderShipmentStatus status,
        String carrierCode,
        String trackingNumber,
        Instant shippedAt,
        Instant deliveredAt,
        List<Item> items) {
    public OrderShipmentResponse {
        items = List.copyOf(items);
    }

    public record Item(UUID orderItemId, int quantity) { }
}
