package com.atlas.catalog.dto.response;

import java.util.UUID;

public record ProductCategoryResponse(UUID categoryId, UUID sellerId, String code, String name) {
}
