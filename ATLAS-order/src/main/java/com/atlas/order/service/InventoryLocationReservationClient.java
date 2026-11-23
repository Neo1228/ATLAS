package com.atlas.order.service;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface InventoryLocationReservationClient {
    RemoteReservation reserve(UUID fulfillmentLocationId, UUID orderId, String idempotencyKey,
            List<ReservationItem> items);

    void cancel(UUID fulfillmentLocationId, UUID orderId, String idempotencyKey);
    RemoteReservation claim(UUID fulfillmentLocationId, UUID reservationId);

    RemoteReservation release(UUID fulfillmentLocationId, UUID reservationId);

    /** 결제 매입 전에 claim한 재고를 판매 가능 수량으로 되돌린다. 이미 되돌렸으면 같은 결과를 반환한다. */
    RemoteReservation restore(UUID fulfillmentLocationId, UUID reservationId);

    /** 판매 확정 재고 일부를 환불·반품 재입고한다. 같은 restock key 재호출은 멱등이다. */
    RemoteReservation restock(UUID fulfillmentLocationId, UUID reservationId, String restockKey,
            List<ReservationItem> items);

    record ReservationItem(UUID sellerId, String skuId, int quantity) {
    }

    record RemoteReservation(UUID reservationId, String status, Instant expiresAt) {
    }
}
