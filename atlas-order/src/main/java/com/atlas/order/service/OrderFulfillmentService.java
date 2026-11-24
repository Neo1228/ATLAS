package com.atlas.order.service;

import com.atlas.order.dto.response.OrderViewResponse;
import java.util.UUID;

public interface OrderFulfillmentService {
    OrderViewResponse.Fulfillment find(UUID orderId);
    OrderViewResponse.Fulfillment start(UUID sellerId, UUID orderId);
    void initialize(UUID orderId);
}
