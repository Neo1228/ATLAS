package com.atlas.inventory.location.dto.request;

import jakarta.validation.constraints.NotNull;
import java.util.UUID;

public record InventoryCancellationRequest(@NotNull UUID orderId) {
}
