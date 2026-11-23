package com.atlas.application.persistence.projection;

import java.math.BigDecimal;

public record ReviewSummary(long reviewCount, BigDecimal averageRating) {
}
