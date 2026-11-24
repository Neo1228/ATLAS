package com.atlas.inventorylocation.persistence.projection;

import java.util.UUID;

public record LocationStockRecord(UUID fulfillmentLocationId, UUID sellerId, String skuId, long initialCapacity,
                          long availableQuantity, long reservedQuantity, long claimedQuantity) { }
