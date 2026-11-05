package com.atlas.order.persistence.projection;

import com.atlas.order.persistence.state.OrderRefundStatus;
import java.time.Instant;
import java.util.UUID;

public record RefundHeader(UUID refundId, UUID orderId, UUID sellerId, String idempotencyKey,
                           long recordedAmountWon, OrderRefundStatus status, Instant createdAt) {
}
