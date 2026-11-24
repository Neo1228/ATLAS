package com.atlas.inventorylocation.dto.response;

import java.util.UUID;

/** 특정 Seller SKU의 location별 판매 가능 수량. 미등록 SKU는 0으로 반환한다. */
public record InventoryAvailabilityResponse(UUID fulfillmentLocationId, UUID sellerId, String skuId, long available) {
}
