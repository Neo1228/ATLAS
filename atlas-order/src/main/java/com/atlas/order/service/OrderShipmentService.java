package com.atlas.order.service;

import com.atlas.order.dto.response.OrderViewResponse;
import com.atlas.order.dto.request.OrderRequest;
import java.util.List;
import java.util.UUID;

public interface OrderShipmentService {
    OrderViewResponse.Shipment create(OrderRequest.Shipment request);
    List<OrderViewResponse.Shipment> findAll(String buyerRef, UUID orderId);
    OrderViewResponse.Shipment deliver(UUID sellerId, UUID orderId, UUID shipmentId);
}
