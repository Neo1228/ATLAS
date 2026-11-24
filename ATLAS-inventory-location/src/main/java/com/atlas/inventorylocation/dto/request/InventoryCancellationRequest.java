package com.atlas.inventorylocation.dto.request;

import jakarta.validation.constraints.NotNull;
import java.util.UUID;

public record InventoryCancellationRequest(@NotNull UUID orderId) {
}
