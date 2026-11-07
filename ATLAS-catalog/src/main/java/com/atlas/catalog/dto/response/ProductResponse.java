package com.atlas.catalog.dto.response;

import java.util.UUID;

public record ProductResponse(UUID productId, UUID sellerId, String name, String description, UUID categoryId) {
    public ProductResponse(UUID productId, UUID sellerId, String name, String description) {
        this(productId, sellerId, name, description, null);
    }
}
