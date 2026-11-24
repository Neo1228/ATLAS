package com.atlas.order.persistence.projection;

import java.math.BigDecimal;

public record ProductReviewSummary(long reviewCount, BigDecimal averageRating) {
}
