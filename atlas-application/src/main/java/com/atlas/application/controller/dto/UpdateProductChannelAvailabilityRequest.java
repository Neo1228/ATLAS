package com.atlas.application.controller.dto;

import com.atlas.catalog.model.ProductSalesChannel;
import jakarta.validation.constraints.NotNull;

public record UpdateProductChannelAvailabilityRequest(@NotNull ProductSalesChannel channel, @NotNull Boolean available) {
}
