package com.atlas.order.dto.response;

import com.atlas.order.persistence.state.OrderReturnInspectionResult;
import com.atlas.order.persistence.state.OrderReturnStatus;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record OrderReturnResponse(UUID returnId, UUID orderId, UUID sellerId, String buyerRef, OrderReturnStatus status,
                                  Instant requestedAt, Instant receivedAt, Instant inspectedAt, List<Item> items) {
    public OrderReturnResponse { items = List.copyOf(items); }
    public record Item(UUID orderItemId, int quantity, OrderReturnInspectionResult inspectionResult,
                       UUID refundId, Long refundAmountWon, Instant inspectedAt) { }
}
