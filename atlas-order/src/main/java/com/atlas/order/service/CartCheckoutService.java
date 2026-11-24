package com.atlas.order.service;

import com.atlas.order.dto.request.SellerCouponRequest;
import com.atlas.order.dto.response.CheckoutResponse;
import java.util.List;

public interface CartCheckoutService {
    CheckoutResponse checkout(String buyerRef, String idempotencyKey);
    CheckoutResponse checkout(String buyerRef, String idempotencyKey, List<SellerCouponRequest> coupons);
}
