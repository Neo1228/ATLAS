package com.atlas.order.dto.response;

import com.atlas.inventory.persistence.state.ReservationStatus;
import com.atlas.order.persistence.state.OrderSource;
import com.atlas.order.persistence.state.OrderState;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** 판매자가 배송·환불을 구성할 수 있도록 자기 주문의 이행 필드만 제공한다. */
public record SellerOrderDetailResponse(UUID orderId, UUID sellerId, OrderSource source, OrderState state,
                                        Instant createdAt, Instant checkoutExpiresAt,
                                        ReservationStatus inventoryState, UUID fulfillmentLocationId,
                                        List<OrderItemResponse> items, long totalPriceWon,
                                        String sellerCouponCode, long couponDiscountWon) {
    public SellerOrderDetailResponse {
        items = List.copyOf(items);
    }
}
