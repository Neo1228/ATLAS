package com.atlas.order.dto.response;

import com.atlas.order.model.OrderRefundPaymentStatus;
import com.atlas.order.model.OrderReturnInspectionResult;
import com.atlas.order.model.OrderReturnStatus;
import com.atlas.order.model.ReturnItemCondition;
import com.atlas.order.model.ReturnReason;
import com.atlas.order.model.ReturnRecommendationDecision;
import com.atlas.order.model.ReturnRecommendationReason;
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

    /**
     * 판매자에게 보여 주는 반품 권고(ADR-0110). 상태도 돈도 바꾸지 않는다. 판매자가 따르려면 줄별
     * {@code suggestedResult}를 기존 검수 호출({@code PUT .../inspection})에 보내고, 그 호출만 환불을 실행한다.
     * {@code refundAmountWon}은 서버가 주문 스냅샷으로 계산한 {@code PASSED} 권고 줄의 합계다.
     * {@code HUMAN_REVIEW}이면 규칙이 정하지 못한 줄의 {@code suggestedResult}가 null이다.
     * {@code reasonNote}는 구매자가 쓴 원문이고 결정에는 쓰이지 않았다. buyerRef는 내보내지 않는다.
     */
    public record Recommendation(UUID returnId, UUID orderId, ReturnRecommendationDecision decision,
                                 List<ReturnRecommendationReason> reasons, long refundAmountWon,
                                 long reviewAmountLimitWon, ReturnReason reason, String reasonNote,
                                 Instant evaluatedAt, List<Item> items) {
        public Recommendation {
            reasons = List.copyOf(reasons);
            items = List.copyOf(items);
        }

        public record Item(UUID orderItemId, int quantity, ReturnItemCondition condition,
                           OrderReturnInspectionResult suggestedResult, Long refundAmountWon,
                           Instant returnWindowEndsAt, ReturnRecommendationReason reason) { }
    }

    public record SellerOrderReturnPage(List<SellerOrderReturn> returns, String nextCursor) {
        public SellerOrderReturnPage {
            returns = List.copyOf(returns);
        }
    }
}
