package com.atlas.order.service;

import com.atlas.order.dto.request.LocationReservationItemRequest;
import com.atlas.order.dto.response.RemoteReservationResponse;
import java.util.List;
import java.util.UUID;

public interface LocationClient {
    RemoteReservationResponse reserve(UUID fulfillmentLocationId, UUID orderId, String idempotencyKey,
            List<LocationReservationItemRequest> items);

    void cancel(UUID fulfillmentLocationId, UUID orderId, String idempotencyKey);
    RemoteReservationResponse claim(UUID fulfillmentLocationId, UUID reservationId);

    RemoteReservationResponse release(UUID fulfillmentLocationId, UUID reservationId);

    /** 결제 매입 전에 claim한 재고를 판매 가능 수량으로 되돌린다. 이미 되돌렸으면 같은 결과를 반환한다. */
    RemoteReservationResponse restore(UUID fulfillmentLocationId, UUID reservationId);

    /** 판매 확정 재고 일부를 환불·반품 재입고한다. 같은 restock key 재호출은 멱등이다. */
    RemoteReservationResponse restock(UUID fulfillmentLocationId, UUID reservationId, String restockKey,
            List<LocationReservationItemRequest> items);

    /** 원격 location의 판매 가능 수량(routing 판단용, 조회 시점 값). */
    long availableStock(UUID fulfillmentLocationId, UUID sellerId, String skuId);
}
