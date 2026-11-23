package com.atlas.catalog.dto.response;

import java.time.Instant;
import java.util.UUID;

/** 구매자가 저장한 상품의 식별자와 저장 시각입니다. */
public record BuyerWishlistItemResponse(UUID productId, Instant savedAt) {
}
