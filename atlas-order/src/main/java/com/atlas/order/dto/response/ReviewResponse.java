package com.atlas.order.dto.response;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class ReviewResponse {
    private ReviewResponse() {
    }

    /** Verified buyer가 남긴 공개 review입니다. 구매자 식별자는 노출하지 않습니다. */
    public record ProductReview(UUID reviewId, int rating, String body, Instant createdAt) {
    }

    /** Product별 평점 집계와 제한된 review 페이지입니다. */
    public record Page(UUID productId, BigDecimal averageRating, long reviewCount,
            List<ProductReview> items, int limit, int offset, boolean hasMore) {
        public Page {
            items = List.copyOf(items);
        }
    }
}
