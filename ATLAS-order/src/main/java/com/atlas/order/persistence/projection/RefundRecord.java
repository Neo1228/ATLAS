package com.atlas.order.persistence.projection;

import com.atlas.order.persistence.state.OrderRefundStatus;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record RefundRecord(UUID refundId, UUID orderId, UUID sellerId, String idempotencyKey,
                           long recordedAmountWon, OrderRefundStatus status, Instant createdAt,
                           List<RefundItem> items) {
    public RefundRecord {
        items = List.copyOf(items);
    }
}
