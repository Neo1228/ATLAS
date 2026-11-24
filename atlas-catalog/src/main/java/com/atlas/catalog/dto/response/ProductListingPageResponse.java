package com.atlas.catalog.dto.response;

import java.util.List;

public record ProductListingPageResponse(List<ProductListingResponse> items, String nextCursor) {
    public ProductListingPageResponse { items = List.copyOf(items); }
}
