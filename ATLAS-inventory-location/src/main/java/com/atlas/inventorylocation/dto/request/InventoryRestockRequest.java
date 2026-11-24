package com.atlas.inventorylocation.dto.request;

import com.atlas.inventorylocation.dto.request.InventoryReservationRequest.Item;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import java.util.List;

public record InventoryRestockRequest(@NotEmpty List<@Valid Item> items) {
}
