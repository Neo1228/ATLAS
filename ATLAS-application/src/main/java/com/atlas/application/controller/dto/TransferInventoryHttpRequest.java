package com.atlas.application.controller.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.UUID;

public record TransferInventoryHttpRequest(
        @NotBlank @Size(max = 128) String skuId,
        @NotNull UUID sourceLocationId,
        @NotNull UUID destinationLocationId,
        @Min(1) int quantity,
        @NotBlank @Size(max = 256) String reason) {}
