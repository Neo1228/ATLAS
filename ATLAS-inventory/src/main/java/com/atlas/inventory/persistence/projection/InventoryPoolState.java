package com.atlas.inventory.persistence.projection;

public record InventoryPoolState(int initialCapacity, int available, int reserved, int claimed) {}
