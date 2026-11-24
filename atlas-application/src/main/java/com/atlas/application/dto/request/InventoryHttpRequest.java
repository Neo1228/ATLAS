package com.atlas.application.dto.request;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.UUID;

public final class InventoryHttpRequest {
    private InventoryHttpRequest() {
    }

    public record AdjustInventoryCapacity(int delta, @NotBlank @Size(max = 256) String reason) {}

    public record CreateFulfillmentLocation(
            @NotBlank @Size(max = 64) String code,
            @NotBlank @Size(max = 128) String name,
            @NotNull @Min(0) Integer priority) {}

    public record TransferInventory(
            @NotBlank @Size(max = 128) String skuId,
            @NotNull UUID sourceLocationId,
            @NotNull UUID destinationLocationId,
            @Min(1) int quantity,
            @NotBlank @Size(max = 256) String reason) {}

    public record UpdateFulfillmentLocation(
            @NotBlank @Size(max = 128) String name,
            @NotNull @Min(0) Integer priority,
            @NotNull Boolean active) {}
}
