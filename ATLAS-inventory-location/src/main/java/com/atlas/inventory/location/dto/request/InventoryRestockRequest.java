package com.atlas.inventory.location.dto.request;

import com.atlas.inventory.location.dto.request.InventoryReservationRequest.Item;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import java.util.List;

public record InventoryRestockRequest(@NotEmpty List<@Valid Item> items) {
}
