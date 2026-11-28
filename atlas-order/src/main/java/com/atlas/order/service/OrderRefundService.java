package com.atlas.order.service;

import com.atlas.order.dto.response.RefundResponse;
import com.atlas.order.dto.request.OrderRequest;
import java.util.UUID;

public interface OrderRefundService {
    RefundResponse.Refund refund(OrderRequest.Refund request);

    /**
     * 정지·폐쇄된 Seller의 결제 완료·출고 전 주문을 운영자가 남은 수량 전부 환불한다.
     *
     * <p>Seller 환불({@link #refund})과 같은 예약·PSP 환불·정산 경로를 쓰고, 운영자 식별자만
     * {@code domain_audit_event}에 더 남긴다. 활성 Seller의 주문은 거절한다(ADR-0112).
     */
    RefundResponse.Refund refundForDisabledSeller(OrderRequest.OperatorRefund request);
    RefundResponse.Refund processInspectedReturnRefund(UUID returnId);
    RefundResponse.Refund prepareInspectedReturnRefund(UUID returnId);
    RefundResponse.Refund reconcilePayPalRefund(UUID paymentOperationId);
    RefundResponse.RefundSummary find(UUID sellerId, UUID orderId);
}
