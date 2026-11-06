package com.atlas.order.service;

import com.atlas.order.dto.response.CheckoutResponse;
import java.util.UUID;

public interface CheckoutPaymentService {
    CheckoutResponse submit(UUID checkoutId);
    CheckoutResponse reconcile(UUID checkoutId, String idempotencyKey);
    CheckoutResponse cancel(UUID checkoutId, String idempotencyKey);
    CheckoutResponse find(UUID checkoutId);
}
