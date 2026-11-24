package com.atlas.inventorylocation.dto.request;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;

public record InventoryReservationRequest(@NotNull UUID orderId, @NotEmpty List<@Valid Item> items) {
    public record Item(@NotNull UUID sellerId, @NotBlank @Size(max = 96) String skuId, @Positive int quantity) {
    }
}
