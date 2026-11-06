package com.atlas.order.dto.request;

import java.util.List;
import java.util.UUID;

public record OrderReturnRequest(String buyerRef, UUID orderId, String idempotencyKey, List<Item> items) {
    public record Item(UUID orderItemId, int quantity) { }
}
