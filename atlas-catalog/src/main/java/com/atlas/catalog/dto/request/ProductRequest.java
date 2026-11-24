package com.atlas.catalog.dto.request;

import java.util.List;
import java.util.UUID;

public final class ProductRequest {
    private ProductRequest() {
    }

    public record CreateProduct(String name, String description, List<UUID> categoryIds) {
        public CreateProduct(String name, String description) {
            this(name, description, null);
        }
    }

    public record RegisterSellerSku(String skuId, long priceWon, int initialStock) {
    }
}
