package com.atlas.order.service;

import com.atlas.order.dto.request.OrderRefundRequest;
import com.atlas.order.dto.response.OrderRefundResponse;
import com.atlas.order.dto.response.OrderRefundSummaryResponse;
import java.util.UUID;

public interface OrderRefundService {
    OrderRefundResponse refund(UUID sellerId, UUID orderId, String idempotencyKey, OrderRefundRequest request);
    OrderRefundSummaryResponse find(UUID sellerId, UUID orderId);
}
