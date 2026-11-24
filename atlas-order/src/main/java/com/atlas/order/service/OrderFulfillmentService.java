package com.atlas.order.service;

import com.atlas.order.dto.response.OrderFulfillmentResponse;
import java.util.UUID;

public interface OrderFulfillmentService {
    OrderFulfillmentResponse find(UUID orderId);
    OrderFulfillmentResponse start(UUID sellerId, UUID orderId);
    void initialize(UUID orderId);
}
