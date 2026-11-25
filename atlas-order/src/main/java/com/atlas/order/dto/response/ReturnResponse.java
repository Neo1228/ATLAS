package com.atlas.order.dto.response;

import com.atlas.order.model.OrderRefundPaymentStatus;
import com.atlas.order.model.OrderReturnInspectionResult;
import com.atlas.order.model.OrderReturnStatus;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class ReturnResponse {
    private ReturnResponse() {
    }

    public record OrderReturn(UUID returnId, UUID orderId, UUID sellerId, String buyerRef, OrderReturnStatus status,
                                      OrderRefundPaymentStatus paymentStatus, Instant requestedAt, Instant receivedAt,
                                      Instant inspectedAt, List<Item> items) {
        public OrderReturn { items = List.copyOf(items); }
        public record Item(UUID orderItemId, int quantity, UUID shipmentId,
                           OrderReturnInspectionResult inspectionResult, UUID refundId, Long refundAmountWon,
                           Instant inspectedAt) {
            public Item(UUID orderItemId, int quantity, OrderReturnInspectionResult inspectionResult,
                    UUID refundId, Long refundAmountWon, Instant inspectedAt) {
                this(orderItemId, quantity, null, inspectionResult, refundId, refundAmountWon, inspectedAt);
            }
        }
    }

    /**
     * 판매자에게는 자기 반품의 처리 필드만 반환하고 buyerRef 등 구매자 식별 정보는 제외한다.
     * {@code inspectionDueAt}은 수령 뒤 검수 마감 시각(배타적 끝, 수령 전에는 null)이고, {@code inspectionOverdue}는
     * 상태가 RECEIVED이고 마감 시각 이후일 때만 true다. 표시일 뿐 상태나 환불을 바꾸지 않는다(ADR-0089).
     */
    public record SellerOrderReturn(UUID returnId, UUID orderId, UUID sellerId, OrderReturnStatus status,
                                            OrderRefundPaymentStatus paymentStatus, Instant requestedAt,
                                            Instant receivedAt, Instant inspectedAt, Instant inspectionDueAt,
                                            boolean inspectionOverdue, List<Item> items) {
        public SellerOrderReturn {
            items = List.copyOf(items);
        }

        public record Item(UUID orderItemId, int quantity, OrderReturnInspectionResult inspectionResult,
                           UUID refundId, Long refundAmountWon, Instant inspectedAt) { }
    }

    public record SellerOrderReturnPage(List<SellerOrderReturn> returns, String nextCursor) {
        public SellerOrderReturnPage {
            returns = List.copyOf(returns);
        }
    }
}
