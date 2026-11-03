package com.atlas.payment.service;

import com.atlas.payment.dto.response.PaymentResponse;
import java.util.UUID;

public interface PaymentService {

    PaymentResponse submitInitial(UUID orderId);

    PaymentResponse reconcileForCancellation(UUID orderId);
    PaymentResponse reconcile(UUID orderId);

    PaymentResponse voidAuthorization(UUID orderId);

    PaymentResponse find(UUID orderId);
}
