package com.atlas.order.persistence.projection;

import java.util.UUID;

public record ReservationWorkflowItemRecord(UUID sellerId, String skuId, int quantity) {
}
