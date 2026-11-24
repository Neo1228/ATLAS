package com.atlas.inventory.dto.response;

import java.util.UUID;

public record InventoryLocationResponse(UUID locationId, UUID sellerId, String code, String name,
        int priority, boolean active) {}
