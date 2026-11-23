package com.atlas.order.dto.response;

import java.time.Instant;
import java.util.UUID;

/** Verified buyer가 남긴 공개 review입니다. 구매자 식별자는 노출하지 않습니다. */
public record ProductReviewResponse(UUID reviewId, int rating, String body, Instant createdAt) {
}
