package com.atlas.order.service;

import com.atlas.order.dto.request.OrderRefundRequest;
import com.atlas.order.dto.response.OrderRefundResponse;
import com.atlas.order.dto.response.OrderRefundSummaryResponse;
import java.util.UUID;

public interface OrderRefundService {
    OrderRefundResponse refund(OrderRefundRequest request);
    OrderRefundResponse recordInspectedReturnRefund(UUID returnId);
    OrderRefundSummaryResponse find(UUID sellerId, UUID orderId);
}
