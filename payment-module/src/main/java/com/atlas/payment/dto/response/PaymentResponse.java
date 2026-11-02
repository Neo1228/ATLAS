package com.atlas.payment.dto.response;

import com.atlas.payment.persistence.state.PaymentState;
import java.util.UUID;

public record PaymentResponse(UUID orderId, UUID attemptId, PaymentState state, boolean authorizationActive) {
}
