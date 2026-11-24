package com.atlas.order.dto.response;

import java.util.UUID;

/**
 * 주문 한 줄.
 *
 * <p>{@code orderItemId} 는 배송·환불·반품 검수 API 가 모두 요구하는 식별자다. 이 필드가
 * 응답에 없으면 판매자는 HTTP 만으로 이행을 시작할 수 없다. 실제로 이 저장소의 통합
 * 테스트들이 이 값을 JDBC 로 직접 조회해 우회하고 있었다.
 */
public record OrderItemResponse(UUID orderItemId, UUID productId, UUID sellerId, String skuId,
                                String productName, int quantity, long unitPriceWon,
                                long couponDiscountWon, long lineTotalWon) {
    public OrderItemResponse(UUID productId, UUID sellerId, String skuId, String productName,
                             int quantity, long unitPriceWon) {
        this(null, productId, sellerId, skuId, productName, quantity, unitPriceWon, 0,
                Math.multiplyExact(unitPriceWon, quantity));
    }
}
