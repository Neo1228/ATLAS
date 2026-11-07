package com.atlas.inventory.persistence.projection;

import java.util.UUID;

public record InventoryLocationRecord(
        UUID locationId,
        UUID sellerId,
        String code,
        String name,
        int priority,
        boolean active) {
}
