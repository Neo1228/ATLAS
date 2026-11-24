package com.atlas.inventory.dto.request;

import java.util.UUID;

public record UpdateFulfillmentLocationRequest(UUID sellerId, UUID locationId, String name, int priority, boolean active) {}
