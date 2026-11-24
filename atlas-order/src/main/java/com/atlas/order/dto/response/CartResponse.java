package com.atlas.order.dto.response;

import java.util.List;
import java.util.UUID;

/** cartId 가 null 이면 아직 DB에 cart를 만들지 않은 빈 장바구니를 뜻한다. */
public record CartResponse(UUID cartId, String buyerRef, List<CartItemResponse> items) {
    public CartResponse {
        items = List.copyOf(items);
    }

    public static CartResponse empty(String buyerRef) {
        return new CartResponse(null, buyerRef, List.of());
    }
}
