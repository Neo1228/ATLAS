package com.atlas.order.dto.request;

import java.util.List;
import java.util.UUID;

public record OrderReturnRequest(String buyerRef, UUID orderId, String idempotencyKey, List<Item> items) {
    /** 분할 출고 반품은 출처 shipment를 지정한다. 유일한 적격 출처면 기존 요청처럼 생략할 수 있다. */
    public record Item(UUID orderItemId, int quantity, UUID shipmentId) {
        public Item(UUID orderItemId, int quantity) { this(orderItemId, quantity, null); }
    }
}
