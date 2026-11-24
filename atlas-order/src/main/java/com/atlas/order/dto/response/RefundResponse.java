package com.atlas.order.dto.response;

import com.atlas.order.model.OrderRefundPaymentStatus;
import com.atlas.order.model.OrderRefundStatus;
import java.time.Instant;
import java.util.UUID;

public final class RefundResponse {
    private RefundResponse() {
    }

    public record Refund(UUID refundId, UUID orderId, long refundAmountWon, OrderRefundStatus status,
                                      OrderRefundPaymentStatus paymentStatus, String providerRefundId, Instant createdAt) {
    }

    public record RefundSummary(UUID orderId, OrderRefundStatus status, long totalRecordedAmountWon,
                                             Instant updatedAt) {
    }
}
