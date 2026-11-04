package com.atlas.cart.dto.response;

import java.util.UUID;

public record CartItemResponse(UUID productId, String skuId, int quantity, String productName, long priceWon) {
}
