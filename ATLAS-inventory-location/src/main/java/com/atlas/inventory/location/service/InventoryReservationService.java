package com.atlas.inventory.location.service;

import com.atlas.inventory.location.dto.request.InventoryCancellationRequest;
import com.atlas.inventory.location.dto.request.InventoryReservationRequest;
import com.atlas.inventory.location.dto.request.InventoryReservationRequest.Item;
import com.atlas.inventory.location.dto.request.LocationStockRequest;
import com.atlas.inventory.location.dto.response.InventoryReservationResponse;
import com.atlas.inventory.location.dto.response.InventoryStockResponse;
import java.util.List;
import java.util.UUID;

public interface InventoryReservationService {
    InventoryStockResponse setInitialCapacity(UUID fulfillmentLocationId, UUID sellerId, String skuId,
            LocationStockRequest request);

    InventoryStockResponse findStock(UUID fulfillmentLocationId, UUID sellerId, String skuId);

    /** 라우팅용 조회. 없는 판매자·SKU pool은 0으로 보고, 알 수 없는 location은 계속 NOT_FOUND다. */
    long availableStock(UUID fulfillmentLocationId, UUID sellerId, String skuId);

    InventoryReservationResponse reserve(UUID fulfillmentLocationId, String idempotencyKey,
            InventoryReservationRequest request);

    InventoryReservationResponse cancel(UUID fulfillmentLocationId, String idempotencyKey,
            InventoryCancellationRequest request);

    InventoryReservationResponse findReservation(UUID fulfillmentLocationId, UUID reservationId);

    InventoryReservationResponse claim(UUID fulfillmentLocationId, UUID reservationId);

    /**
     * 결제 매입이 확정되지 않은 claim을 되돌린다. claim 뒤 매입 거절·취소가 발생하면 판매되지 않은
     * 재고를 다시 판매 가능 수량으로 돌려야 하므로 CLAIMED만 RELEASED로 전이한다. 반복 호출은 멱등이다.
     */
    InventoryReservationResponse restore(UUID fulfillmentLocationId, UUID reservationId);

    /**
     * 환불·반품 검수 통과로 판매 확정(CLAIMED) 재고 일부를 판매 가능 수량으로 되돌린다.
     * 같은 restock key는 한 번만 적용되며, 다른 내용으로 재사용하면 거절한다. item별 누적 restock 수량은
     * reservation item 수량을 넘지 못한다. reservation row lock으로 같은 reservation의 동시 restock을 직렬화한다.
     */
    InventoryReservationResponse restock(UUID fulfillmentLocationId, UUID reservationId, String restockKey,
                                                List<Item> requestedItems);

    InventoryReservationResponse release(UUID fulfillmentLocationId, UUID reservationId);

    int expireDue(int limit);
}
