package com.atlas.order.dto.response;

import com.atlas.order.persistence.state.OrderRefundPaymentStatus;
import com.atlas.order.persistence.state.OrderRefundStatus;
import java.time.Instant;
import java.util.UUID;

public record OrderRefundResponse(UUID refundId, UUID orderId, long refundAmountWon, OrderRefundStatus status,
                                  OrderRefundPaymentStatus paymentStatus, String providerRefundId, Instant createdAt) {
}
