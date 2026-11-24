package com.atlas.application.controller.dto;

import com.atlas.inventory.dto.response.InventoryLocationResponse;
import java.util.UUID;

public record InventoryLocationHttpResponse(UUID locationId, UUID sellerId, String code, String name,
        int priority, boolean active) {
    public static InventoryLocationHttpResponse from(InventoryLocationResponse response) {
        return new InventoryLocationHttpResponse(response.locationId(), response.sellerId(), response.code(),
                response.name(), response.priority(), response.active());
    }
}
