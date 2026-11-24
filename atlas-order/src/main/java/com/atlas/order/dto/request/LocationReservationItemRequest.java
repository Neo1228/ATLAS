package com.atlas.order.dto.request;

import java.util.UUID;

public record LocationReservationItemRequest(UUID sellerId, String skuId, int quantity) {
}
