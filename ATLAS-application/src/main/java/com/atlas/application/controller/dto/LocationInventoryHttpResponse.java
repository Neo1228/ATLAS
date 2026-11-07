package com.atlas.application.controller.dto;

import com.atlas.inventory.dto.response.LocationInventoryResponse;
import java.util.UUID;

public record LocationInventoryHttpResponse(UUID sellerId, UUID locationId, String skuId,
        int initialCapacity, int available, int reserved, int claimed) {
    public static LocationInventoryHttpResponse from(LocationInventoryResponse response) {
        return new LocationInventoryHttpResponse(response.sellerId(), response.locationId(), response.skuId(),
                response.initialCapacity(), response.available(), response.reserved(), response.claimed());
    }
}
