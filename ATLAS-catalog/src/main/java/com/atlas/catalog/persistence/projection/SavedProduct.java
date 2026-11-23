package com.atlas.catalog.persistence.projection;

import java.time.Instant;
import java.util.UUID;

public record SavedProduct(UUID productId, Instant savedAt) {
}
