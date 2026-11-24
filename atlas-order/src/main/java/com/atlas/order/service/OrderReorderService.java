package com.atlas.order.service;

import com.atlas.order.dto.request.OrderRequest;
import com.atlas.order.dto.response.CartResponse;
import java.util.UUID;

public interface OrderReorderService {
    CartResponse reorder(OrderRequest.Reorder request);
}
