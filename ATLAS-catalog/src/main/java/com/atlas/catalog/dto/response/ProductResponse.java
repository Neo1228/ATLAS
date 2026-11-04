package com.atlas.catalog.dto.response;

import java.util.UUID;

public record ProductResponse(UUID productId, UUID sellerId, String name, String description) {
}
