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
    /**
     * PayPal 쪽에서 시작된 환불·역전을 판매자 원장에서 차감한다(ADR-0111). 결제 시도가 한 판매자 주문에만
     * 속하면 기존 환불 분개(보류금 먼저, 그다음 지급 가능액)를 쓰고, 여러 판매자에 걸치면 돈을 움직이지 않고 검토로 넘긴다.
     */
    void applyProviderAdjustment(UUID adjustmentId);
    /**
     * 이 판매자의 송금에서 남겨 둬야 할 진행 중 분쟁 원화 합(ADR-0115). 분쟁 결제 시도가 여러 판매자 주문을 담았으면
     * 누구 몫인지 정할 근거가 없어 담긴 판매자 모두에게 분쟁 금액 전부를 센다.
     */
    long disputeHoldWon(UUID sellerId);
    RefundResponse.RefundSummary find(UUID sellerId, UUID orderId);
}
