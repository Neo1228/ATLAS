package com.atlas.application.controller.dto;

import com.atlas.catalog.model.ProductStatus;
import jakarta.validation.constraints.NotNull;

public record UpdateProductStatusRequest(@NotNull ProductStatus status) {
}
