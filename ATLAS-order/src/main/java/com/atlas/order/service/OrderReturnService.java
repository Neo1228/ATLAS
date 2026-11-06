package com.atlas.order.service;

import com.atlas.order.dto.request.OrderReturnInspectionRequest;
import com.atlas.order.dto.request.OrderReturnRequest;
import com.atlas.order.dto.response.OrderReturnResponse;
import java.util.UUID;

public interface OrderReturnService {
    OrderReturnResponse request(OrderReturnRequest request);
    OrderReturnResponse find(String buyerRef, UUID orderId, UUID returnId);
    OrderReturnResponse receive(UUID sellerId, UUID orderId, UUID returnId);
    OrderReturnResponse inspect(UUID sellerId, UUID orderId, UUID returnId, OrderReturnInspectionRequest request);
}
