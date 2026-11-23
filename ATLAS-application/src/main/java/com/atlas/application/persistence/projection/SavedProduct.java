package com.atlas.application.persistence.projection;

import java.time.Instant;
import java.util.UUID;

public record SavedProduct(UUID productId, Instant savedAt) {
}
