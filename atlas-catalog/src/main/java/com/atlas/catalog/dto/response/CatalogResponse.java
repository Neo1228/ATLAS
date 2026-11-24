package com.atlas.catalog.dto.response;

import com.atlas.catalog.model.ProductSalesChannel;
import com.atlas.catalog.model.ProductStatus;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class CatalogResponse {
    private CatalogResponse() {
    }

    public record Product(UUID productId, UUID sellerId, String name, String description, List<UUID> categoryIds,
            ProductStatus status, List<ProductSalesChannel> availableChannels) {
        public Product {
            categoryIds = categoryIds.stream().sorted().toList();
            availableChannels = availableChannels.stream().sorted().toList();
        }
        public Product(UUID productId, UUID sellerId, String name, String description) {
            this(productId, sellerId, name, description, List.of(), ProductStatus.ACTIVE,
                    List.of(ProductSalesChannel.ONLINE_STORE, ProductSalesChannel.CAMPAIGN));
        }
    }

    public record ProductListing(Product product, List<SellerSku> variants) {
        public ProductListing {
            variants = List.copyOf(variants);
        }
    }

    public record ProductListingPage(List<ProductListing> items, String nextCursor) {
        public ProductListingPage { items = List.copyOf(items); }
    }

    public record ProductCategory(UUID categoryId, UUID sellerId, String code, String name, UUID parentCategoryId) {
    }

    public record SellerSku(
            UUID sellerId,
            UUID productId,
            String skuId,
            String productName,
            long priceWon,
            Long salePriceWon,
            Instant saleStartsAt,
            Instant saleEndsAt,
            long effectivePriceWon) {
    }

    public record Seller(UUID sellerId) {
    }

    public record SellerCoupon(UUID couponId, UUID sellerId, String code,
                                       long discountAmountWon, Instant startsAt, Instant endsAt,
                                       boolean active, Long maxRedemptions, Long reservedCount,
                                       Long redeemedCount, Long remainingCount) {
    }
}
