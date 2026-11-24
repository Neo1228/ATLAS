package com.atlas.catalog.dto.response;

import java.util.List;

public record ProductListingResponse(ProductResponse product, List<SellerSkuResponse> variants) {
    public ProductListingResponse {
        variants = List.copyOf(variants);
    }
}
