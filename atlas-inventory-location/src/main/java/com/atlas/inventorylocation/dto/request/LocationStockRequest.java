package com.atlas.inventorylocation.dto.request;

import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.NotNull;
import java.util.UUID;

public record LocationStockRequest(@NotNull UUID sellerId, @PositiveOrZero long initialCapacity) {
}
