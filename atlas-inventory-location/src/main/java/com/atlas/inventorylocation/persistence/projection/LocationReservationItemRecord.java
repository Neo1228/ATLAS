package com.atlas.inventorylocation.persistence.projection;

import java.util.UUID;

public record LocationReservationItemRecord(UUID sellerId, String skuId, int quantity) { }
