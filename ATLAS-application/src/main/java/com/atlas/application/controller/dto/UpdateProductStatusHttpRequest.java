package com.atlas.application.controller.dto;

import com.atlas.catalog.service.ProductStatus;
import jakarta.validation.constraints.NotNull;

public record UpdateProductStatusHttpRequest(@NotNull ProductStatus status) {
}
