package com.atlas.catalog.dto.request;

import java.util.List;
import java.util.UUID;

public record CreateProductRequest(String name, String description, List<UUID> categoryIds) {
    public CreateProductRequest(String name, String description) {
        this(name, description, null);
    }
}