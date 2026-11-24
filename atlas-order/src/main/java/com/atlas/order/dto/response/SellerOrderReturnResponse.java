package com.atlas.order.dto.response;

import com.atlas.order.model.OrderRefundPaymentStatus;
import com.atlas.order.model.OrderReturnInspectionResult;
import com.atlas.order.model.OrderReturnStatus;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** 판매자에게는 자기 반품의 처리 필드만 반환하고 buyerRef 등 구매자 식별 정보는 제외한다. */
public record SellerOrderReturnResponse(UUID returnId, UUID orderId, UUID sellerId, OrderReturnStatus status,
                                        OrderRefundPaymentStatus paymentStatus, Instant requestedAt,
                                        Instant receivedAt, Instant inspectedAt, List<Item> items) {
    public SellerOrderReturnResponse {
        items = List.copyOf(items);
    }

    public record Item(UUID orderItemId, int quantity, OrderReturnInspectionResult inspectionResult,
                       UUID refundId, Long refundAmountWon, Instant inspectedAt) { }
}
