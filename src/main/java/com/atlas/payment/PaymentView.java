package com.atlas.payment;

import java.util.UUID;

public record PaymentView(UUID orderId, UUID attemptId, PaymentState state, boolean authorizationActive) {
}
