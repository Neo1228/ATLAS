package com.atlas.catalog.dto.response;

import java.util.List;

/** 저장 상품 ID와 저장 시각을 반환하는 제한된 wishlist 페이지입니다. */
public record BuyerWishlistPage(List<BuyerWishlistItemResponse> items, int limit, int offset, boolean hasMore) {
    public BuyerWishlistPage {
        items = List.copyOf(items);
    }
}
