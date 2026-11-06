package com.atlas.cart.dto.request;

import java.util.List;
import java.util.UUID;

public record ReorderCartRequest(String buyerRef, boolean confirmExistingCartMerge, List<Item> items) {
    public ReorderCartRequest {
        items = items == null ? null : List.copyOf(items);
    }

    public record Item(UUID sellerId, UUID productId, String skuId, int quantity) {
    }
}
