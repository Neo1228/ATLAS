package com.atlas.order.service;

import com.atlas.order.dto.response.CheckoutResponse;

public interface CartCheckoutService {
    CheckoutResponse checkout(String buyerRef, String idempotencyKey);
}
