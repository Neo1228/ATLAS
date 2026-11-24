package com.atlas.catalog.dto.response;

import com.atlas.catalog.model.ProductSalesChannel;
import com.atlas.catalog.model.ProductStatus;
import java.util.List;
import java.util.UUID;

public record ProductResponse(UUID productId, UUID sellerId, String name, String description, List<UUID> categoryIds,
        ProductStatus status, List<ProductSalesChannel> availableChannels) {
    public ProductResponse {
        categoryIds = categoryIds.stream().sorted().toList();
        availableChannels = availableChannels.stream().sorted().toList();
    }
    public ProductResponse(UUID productId, UUID sellerId, String name, String description) {
        this(productId, sellerId, name, description, List.of(), ProductStatus.ACTIVE,
                List.of(ProductSalesChannel.ONLINE_STORE, ProductSalesChannel.CAMPAIGN));
    }
}