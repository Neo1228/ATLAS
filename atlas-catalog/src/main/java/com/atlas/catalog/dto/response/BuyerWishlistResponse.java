package com.atlas.catalog.dto.response;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class BuyerWishlistResponse {
    private BuyerWishlistResponse() {
    }

    /** 구매자가 저장한 상품의 식별자와 저장 시각입니다. */
    public record Item(UUID productId, Instant savedAt) {
    }

    /** 저장 상품 ID와 저장 시각을 반환하는 제한된 wishlist 페이지입니다. */
    public record Page(List<Item> items, int limit, int offset, boolean hasMore) {
        public Page {
            items = List.copyOf(items);
        }
    }
}
