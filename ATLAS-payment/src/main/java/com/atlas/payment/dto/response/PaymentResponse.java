package com.atlas.payment.dto.response;

import com.atlas.payment.persistence.state.PaymentState;
import java.util.UUID;

/** Payment result with exactly one non-null owner identifier. */
public record PaymentResponse(UUID orderId, UUID checkoutId, UUID attemptId, PaymentState state,
                              boolean authorizationActive) {

    /** Compatibility constructor for callers that only create order-owned attempts. */
    public PaymentResponse(UUID orderId, UUID attemptId, PaymentState state, boolean authorizationActive) {
        this(orderId, null, attemptId, state, authorizationActive);
    }
}
