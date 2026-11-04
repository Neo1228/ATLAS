package com.atlas.order.dto.response;

import com.atlas.order.persistence.state.OrderRefundStatus;
import java.time.Instant;
import java.util.UUID;

public record OrderRefundSummaryResponse(UUID orderId, OrderRefundStatus status, long totalRecordedAmountWon,
                                         String executionMode, Instant updatedAt) {
}
