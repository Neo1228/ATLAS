package com.atlas.catalog.dto.response;

import java.time.Instant;
import java.util.UUID;

public record SellerSkuResponse(
        UUID sellerId,
        UUID productId,
        String skuId,
        String productName,
        long priceWon,
        Long salePriceWon,
        Instant saleStartsAt,
        Instant saleEndsAt,
        long effectivePriceWon) {
}
