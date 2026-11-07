package com.atlas.inventory.dto.request;

import java.util.UUID;

public record CreateFulfillmentLocationRequest(UUID sellerId, String code, String name, int priority) {}
