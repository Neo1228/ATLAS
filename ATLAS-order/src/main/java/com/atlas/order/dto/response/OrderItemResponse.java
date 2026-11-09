package com.atlas.order.dto.response;

import java.util.UUID;

public record OrderItemResponse(UUID productId, UUID sellerId, String skuId, String productName,
                                int quantity, long unitPriceWon, long couponDiscountWon,
                                long lineTotalWon) {
    public OrderItemResponse(UUID productId, UUID sellerId, String skuId, String productName,
                             int quantity, long unitPriceWon) {
        this(productId, sellerId, skuId, productName, quantity, unitPriceWon, 0,
                Math.multiplyExact(unitPriceWon, quantity));
    }
}
