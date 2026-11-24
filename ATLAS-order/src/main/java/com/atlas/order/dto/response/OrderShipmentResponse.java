package com.atlas.order.dto.response;

import com.atlas.order.model.OrderShipmentStatus;
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
        List<Item> items,
        UUID fulfillmentLocationId) {
    public OrderShipmentResponse(UUID shipmentId, UUID orderId, String idempotencyKey, OrderShipmentStatus status,
                                 String carrierCode, String trackingNumber, Instant shippedAt, Instant deliveredAt,
                                 List<Item> items) {
        this(shipmentId, orderId, idempotencyKey, status, carrierCode, trackingNumber, shippedAt, deliveredAt,
                items, null);
    }

    public OrderShipmentResponse {
        items = List.copyOf(items);
    }

    public record Item(UUID orderItemId, int quantity) { }
}
