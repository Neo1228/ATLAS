package com.atlas.order.dto.response;

import java.util.UUID;

public record OrderItemResponse(UUID productId, UUID sellerId, String skuId, String productName,
                                int quantity, long unitPriceWon) {
}
