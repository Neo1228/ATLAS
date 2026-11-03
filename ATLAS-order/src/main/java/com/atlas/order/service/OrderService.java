package com.atlas.order.service;

import com.atlas.order.dto.response.OrderResponse;
import java.util.UUID;

public interface OrderService {
    OrderResponse startCheckout(UUID campaignId, UUID ticketId);
    OrderResponse submitPayment(UUID orderId);
    OrderResponse reconcilePayment(UUID orderId);
    OrderResponse cancel(UUID orderId);
    OrderResponse find(UUID orderId);
}
