package com.atlas.order.service;

import com.atlas.order.dto.response.OrderResponse;
import java.util.UUID;

public interface CartCheckoutService {
    OrderResponse checkout(UUID sellerId, String buyerRef, String idempotencyKey);
}
