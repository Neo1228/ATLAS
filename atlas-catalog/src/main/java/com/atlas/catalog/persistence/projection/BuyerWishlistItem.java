package com.atlas.catalog.persistence.projection;

import java.time.Instant;
import java.util.UUID;

public record BuyerWishlistItem(UUID productId, Instant savedAt) {
}
