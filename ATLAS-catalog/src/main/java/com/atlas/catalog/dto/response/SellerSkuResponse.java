package com.atlas.catalog.dto.response;

import java.util.UUID;

public record SellerSkuResponse(UUID sellerId, UUID productId, String skuId, String productName, long priceWon) {
}
