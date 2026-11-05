package com.atlas.payment.service;

import com.atlas.payment.dto.response.PaymentResponse;
import java.util.UUID;

public interface PaymentService {

    PaymentResponse submitInitial(UUID orderId);

    PaymentResponse reconcileForCancellation(UUID orderId, String idempotencyKey);
    PaymentResponse reconcile(UUID orderId, String idempotencyKey);

    PaymentResponse voidAuthorization(UUID orderId, String idempotencyKey);
    PaymentResponse find(UUID orderId);
}
