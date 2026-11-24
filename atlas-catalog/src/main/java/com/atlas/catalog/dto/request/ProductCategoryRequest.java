package com.atlas.catalog.dto.request;

import java.util.List;
import java.util.UUID;

public final class ProductCategoryRequest {
    private ProductCategoryRequest() {
    }

    public record Create(String code, String name) {
    }

    public record UpdateAssignment(List<UUID> categoryIds) {
    }

    public record UpdateParent(UUID parentCategoryId) {
    }

    public record Update(String name) {
    }
}
