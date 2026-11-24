package com.atlas.order.service;

import com.atlas.order.dto.response.RefundResponse;
import com.atlas.order.dto.request.OrderRequest;
import java.util.UUID;

public interface OrderRefundService {
    RefundResponse.Refund refund(OrderRequest.Refund request);
    RefundResponse.Refund processInspectedReturnRefund(UUID returnId);
    RefundResponse.Refund prepareInspectedReturnRefund(UUID returnId);
    RefundResponse.Refund reconcilePayPalRefund(UUID paymentOperationId);
    RefundResponse.RefundSummary find(UUID sellerId, UUID orderId);
}
