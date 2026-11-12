package com.atlas.order.service;

import com.atlas.order.dto.response.OrderResponse;
import com.atlas.order.dto.response.OrderHistoryPageResponse;
import java.util.UUID;

public interface OrderService {
    OrderResponse startCheckout(UUID campaignId, UUID ticketId);
    OrderResponse submitPayment(UUID orderId);
    OrderResponse completePaymentApproval(UUID orderId, String providerOrderId);
    OrderResponse reconcilePayment(UUID orderId, String idempotencyKey);
    OrderResponse cancel(UUID orderId, String idempotencyKey);
    OrderResponse find(UUID orderId);
    boolean isOwnedByBuyer(UUID orderId, String buyerRef);
    OrderHistoryPageResponse findBuyerOrderHistory(String buyerRef, Integer limit, String cursor);
}
