package com.atlas.order.service;

import com.atlas.order.dto.response.OrderResponse;
import com.atlas.order.dto.response.OrderHistoryPageResponse;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface OrderService {
    OrderResponse startCheckout(UUID campaignId, UUID ticketId);
    OrderResponse submitPayment(UUID orderId);
    OrderResponse completePaymentApproval(UUID orderId, String providerOrderId);
    OrderResponse reconcilePayment(UUID orderId, String idempotencyKey);
    OrderResponse cancel(UUID orderId, String idempotencyKey);
    OrderResponse find(UUID orderId);

    /**
     * 여러 주문의 응답을 한 transaction에서 조립한다. checkout 응답처럼 child order를
     * 한 번에 직렬화하는 읽기 전용 경로에서 주문별 transaction·조회 반복을 피한다.
     */
    List<OrderResponse> findAll(List<UUID> orderIds);
    boolean isOwnedByBuyer(UUID orderId, String buyerRef);
    OrderHistoryPageResponse findBuyerOrderHistory(String buyerRef, Integer limit, String cursor);
    Optional<UUID> findDeliveredOrderItemId(String buyerRef, UUID productId);
}
