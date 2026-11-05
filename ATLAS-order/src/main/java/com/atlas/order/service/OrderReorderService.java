package com.atlas.order.service;

import com.atlas.cart.dto.response.CartResponse;
import com.atlas.order.dto.request.OrderReorderRequest;
import java.util.UUID;

public interface OrderReorderService {
    CartResponse reorder(OrderReorderRequest request);
}
