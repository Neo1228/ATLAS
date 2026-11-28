package com.atlas.order.dto.response;

import java.util.List;
import java.util.UUID;

/** checkout 전 서버가 계산한 장바구니 합계. 금액은 모두 원 단위이고 쿠폰은 반영하지 않는다. */
public record CheckoutPreviewResponse(String buyerRef, List<Line> lines, long totalWon) {
    public CheckoutPreviewResponse {
        lines = List.copyOf(lines);
    }

    public record Line(UUID sellerId, UUID productId, String skuId, String productName, int quantity,
                       long unitPriceWon, long lineTotalWon) {
    }
}
