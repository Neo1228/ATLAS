package com.atlas.order.dto.response;

import com.atlas.order.model.OrderRefundPaymentStatus;
import com.atlas.order.model.OrderReturnInspectionResult;
import com.atlas.order.model.OrderReturnStatus;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record OrderReturnResponse(UUID returnId, UUID orderId, UUID sellerId, String buyerRef, OrderReturnStatus status,
                                  OrderRefundPaymentStatus paymentStatus, Instant requestedAt, Instant receivedAt,
                                  Instant inspectedAt, List<Item> items) {
    public OrderReturnResponse { items = List.copyOf(items); }
    public record Item(UUID orderItemId, int quantity, UUID shipmentId,
                       OrderReturnInspectionResult inspectionResult, UUID refundId, Long refundAmountWon,
                       Instant inspectedAt) {
        public Item(UUID orderItemId, int quantity, OrderReturnInspectionResult inspectionResult,
                UUID refundId, Long refundAmountWon, Instant inspectedAt) {
            this(orderItemId, quantity, null, inspectionResult, refundId, refundAmountWon, inspectedAt);
        }
    }
}
