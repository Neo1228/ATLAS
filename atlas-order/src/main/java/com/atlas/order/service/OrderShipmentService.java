package com.atlas.order.service;

import com.atlas.order.dto.request.OrderShipmentRequest;
import com.atlas.order.dto.response.OrderShipmentResponse;
import java.util.List;
import java.util.UUID;

public interface OrderShipmentService {
    OrderShipmentResponse create(OrderShipmentRequest request);
    List<OrderShipmentResponse> findAll(String buyerRef, UUID orderId);
    OrderShipmentResponse deliver(UUID sellerId, UUID orderId, UUID shipmentId);
}
