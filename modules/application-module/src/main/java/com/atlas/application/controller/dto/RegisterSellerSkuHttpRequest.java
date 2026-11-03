package com.atlas.application.controller.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

public record RegisterSellerSkuHttpRequest(
        @NotBlank @Size(max = 128) String skuId,
        @Positive int initialStock,
        @Positive int maxActiveCheckouts) {
}
