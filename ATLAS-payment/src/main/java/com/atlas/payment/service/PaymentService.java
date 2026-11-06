package com.atlas.payment.service;

import com.atlas.payment.dto.response.PaymentResponse;
import java.util.UUID;

public interface PaymentService {

    PaymentResponse submitInitial(UUID orderId);

    PaymentResponse reconcileForCancellation(UUID orderId, String idempotencyKey);
    PaymentResponse reconcile(UUID orderId, String idempotencyKey);

    PaymentResponse voidAuthorization(UUID orderId, String idempotencyKey);
    PaymentResponse find(UUID orderId);

    /** Creates or resumes the unique payment attempt owned by a buyer checkout. */
    PaymentResponse submitCheckout(UUID checkoutId);

    /** Reconciles the existing checkout attempt using the caller's stable idempotency key. */
    PaymentResponse reconcileCheckout(UUID checkoutId, String idempotencyKey);

    PaymentResponse reconcileCheckoutForCancellation(UUID checkoutId, String idempotencyKey);

    /** Voids an active authorization on a checkout-owned attempt. */
    PaymentResponse voidCheckoutAuthorization(UUID checkoutId, String idempotencyKey);


    /** Returns the payment attempt for a checkout, or {@code null} when none exists. */

    PaymentResponse findCheckout(UUID checkoutId);
}
