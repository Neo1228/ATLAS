package com.atlas.order.service;

import com.atlas.order.dto.response.OrderFulfillmentResponse;
import com.atlas.order.persistence.state.FulfillmentStatus;
import java.util.UUID;

public interface OrderFulfillmentService {
    OrderFulfillmentResponse find(UUID orderId);
    OrderFulfillmentResponse updateStatus(UUID sellerId, UUID orderId, FulfillmentStatus status);
    void initialize(UUID orderId);
}
