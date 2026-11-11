package com.atlas.application.controller.dto;

import com.atlas.catalog.persistence.state.ProductSalesChannel;
import jakarta.validation.constraints.NotNull;

public record UpdateProductChannelAvailabilityHttpRequest(@NotNull ProductSalesChannel channel, @NotNull Boolean available) {
}
