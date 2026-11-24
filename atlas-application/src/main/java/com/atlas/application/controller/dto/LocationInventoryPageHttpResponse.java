package com.atlas.application.controller.dto;

import com.atlas.inventory.dto.response.LocationInventoryPageResponse;
import java.util.List;

public record LocationInventoryPageHttpResponse(List<LocationInventoryHttpResponse> items, String nextCursor) {
    public static LocationInventoryPageHttpResponse from(LocationInventoryPageResponse page) {
        return new LocationInventoryPageHttpResponse(
                page.items().stream().map(LocationInventoryHttpResponse::from).toList(), page.nextCursor());
    }
}
