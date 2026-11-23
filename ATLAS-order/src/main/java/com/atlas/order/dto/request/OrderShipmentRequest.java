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
        List<Item> items,
        UUID fulfillmentLocationId) {
    /** 단일 location 주문용. 출고 location은 주문의 할당에서 정한다. */
    public OrderShipmentRequest(UUID sellerId, UUID orderId, String idempotencyKey, String carrierCode,
                                String trackingNumber, List<Item> items) {
        this(sellerId, orderId, idempotencyKey, carrierCode, trackingNumber, items, null);
    }

    public OrderShipmentRequest {
        items = items == null ? null : Collections.unmodifiableList(new ArrayList<>(items));
    }

    public record Item(UUID orderItemId, int quantity) { }
}
