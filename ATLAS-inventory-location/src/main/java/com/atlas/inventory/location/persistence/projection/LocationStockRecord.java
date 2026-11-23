package com.atlas.inventory.location.persistence.projection;

import java.util.UUID;

public record LocationStockRecord(UUID fulfillmentLocationId, UUID sellerId, String skuId, long initialCapacity,
                          long availableQuantity, long reservedQuantity, long claimedQuantity) { }
