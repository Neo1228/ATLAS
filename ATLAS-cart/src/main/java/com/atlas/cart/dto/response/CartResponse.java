package com.atlas.cart.dto.response;

import java.util.List;
import java.util.UUID;

public record CartResponse(UUID cartId, UUID sellerId, String buyerRef, List<CartItemResponse> items) {
    public CartResponse {
        items = List.copyOf(items);
    }
}
