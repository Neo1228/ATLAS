package com.atlas.order.dto.response;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/** Product별 평점 집계와 제한된 review 페이지입니다. */
public record ProductReviewPage(UUID productId, BigDecimal averageRating, long reviewCount,
        List<ProductReviewResponse> items, int limit, int offset, boolean hasMore) {
    public ProductReviewPage {
        items = List.copyOf(items);
    }
}
