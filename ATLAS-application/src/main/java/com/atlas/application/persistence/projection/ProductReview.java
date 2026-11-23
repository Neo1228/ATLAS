package com.atlas.application.persistence.projection;

import java.time.Instant;
import java.util.UUID;

public record ProductReview(UUID reviewId, int rating, String body, Instant createdAt) {
}
