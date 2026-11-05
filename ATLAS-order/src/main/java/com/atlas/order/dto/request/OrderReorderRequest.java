package com.atlas.order.dto.request;

import java.util.UUID;

public record OrderReorderRequest(UUID orderId, String idempotencyKey, boolean confirmMerge) {
}
