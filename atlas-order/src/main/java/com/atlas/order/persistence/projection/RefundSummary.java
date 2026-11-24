package com.atlas.order.persistence.projection;

import com.atlas.order.model.OrderRefundStatus;
import java.time.Instant;
import java.util.UUID;

public record RefundSummary(UUID orderId, UUID sellerId, long recordedAmountWon,
                            OrderRefundStatus status, Instant updatedAt) {
}
