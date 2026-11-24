package com.atlas.application.dto.request;

import com.atlas.catalog.model.ProductSalesChannel;
import com.atlas.catalog.model.ProductStatus;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import java.time.Instant;

public final class CatalogHttpRequest {
    private CatalogHttpRequest() {
    }

    public record CreateSellerCoupon(
            @NotBlank
            @Pattern(regexp = "[A-Za-z0-9_-]{3,32}")
            String code,
            @Positive long discountAmountWon,
            @NotNull Instant startsAt,
            @NotNull Instant endsAt,
            @Positive Long maxRedemptions) {
    }

    public record SetSkuSalePrice(
            @Positive long salePriceWon,
            @NotNull Instant startsAt,
            @NotNull Instant endsAt) {
    }

    public record UpdateProductChannelAvailability(@NotNull ProductSalesChannel channel, @NotNull Boolean available) {
    }

    public record UpdateProductStatus(@NotNull ProductStatus status) {
    }

    public record UpdateSkuPrice(@Positive long priceWon) {
    }
}
