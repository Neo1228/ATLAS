package com.atlas.order.service;

import com.atlas.cart.dto.response.CartResponse;
import com.atlas.order.dto.request.OrderReorderRequest;

public interface OrderReorderService {
    CartResponse reorder(OrderReorderRequest request);
}
