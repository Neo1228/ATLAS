package com.atlas.order.persistence.projection;

import java.util.UUID;

public record WorkflowItemRecord(UUID sellerId, String skuId, int quantity) {
}
