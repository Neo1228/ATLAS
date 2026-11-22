package com.atlas.inventory.dto.response;

import java.util.List;

/** 위치별 재고 한 페이지. {@code nextCursor}가 null이면 마지막 페이지다. */
public record LocationInventoryPageResponse(List<LocationInventoryResponse> items, String nextCursor) {
    public LocationInventoryPageResponse {
        items = List.copyOf(items);
    }
}
