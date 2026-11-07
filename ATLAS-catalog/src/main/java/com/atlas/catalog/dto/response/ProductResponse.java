package com.atlas.catalog.dto.response;

import com.atlas.catalog.service.ProductSalesChannel;
import com.atlas.catalog.service.ProductStatus;
import java.util.List;
import java.util.UUID;

public record ProductResponse(UUID productId, UUID sellerId, String name, String description, UUID categoryId,
        ProductStatus status, List<ProductSalesChannel> availableChannels) {
    public ProductResponse {
        availableChannels = availableChannels.stream().sorted().toList();
    }

    public ProductResponse(UUID productId, UUID sellerId, String name, String description, UUID categoryId) {
        this(productId, sellerId, name, description, categoryId, ProductStatus.ACTIVE,
                List.of(ProductSalesChannel.ONLINE_STORE, ProductSalesChannel.CAMPAIGN));
    }
    public ProductResponse(UUID productId, UUID sellerId, String name, String description) {
        this(productId, sellerId, name, description, null, ProductStatus.ACTIVE,
                List.of(ProductSalesChannel.ONLINE_STORE, ProductSalesChannel.CAMPAIGN));
    }
}
