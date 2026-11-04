package com.atlas.order.service;

import com.atlas.cart.dto.response.CartResponse;
import java.util.UUID;

public interface OrderReorderService {
    CartResponse reorder(UUID orderId, String idempotencyKey, boolean confirmMerge);
}
