package com.atlas.catalog.dto.request;

import java.util.UUID;

public record CreateProductRequest(String name, String description, UUID categoryId) {
    public CreateProductRequest(String name, String description) {
        this(name, description, null);
    }
}
