package com.atlas.order.persistence.projection;

import com.atlas.order.persistence.state.OrderRefundStatus;
import java.time.Instant;
import java.util.UUID;

public record RefundSummary(UUID orderId, UUID sellerId, long recordedAmountWon,
                            OrderRefundStatus status, Instant updatedAt) {
}
