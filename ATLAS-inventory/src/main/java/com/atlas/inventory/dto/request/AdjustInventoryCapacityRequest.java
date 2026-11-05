package com.atlas.inventory.dto.request;

public record AdjustInventoryCapacityRequest(int delta, String reason) {}
