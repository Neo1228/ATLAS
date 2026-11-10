package com.atlas.payment.provider;

import java.util.UUID;

public record PaymentRequest(PaymentOperation operation, UUID ownerId, UUID attemptId, long amountMinor,
        String currency, String providerOrderId, String providerAuthorizationId,
        String createOrderRequestId, String authorizationRequestId, String captureRequestId,
        String voidRequestId, String returnUrl, String cancelUrl) {
    public PaymentRequest {
        if (operation == null || ownerId == null || attemptId == null || amountMinor <= 0
                || currency == null || currency.isBlank()) {
            throw new IllegalArgumentException("A positive server-derived payment amount is required");
        }
    }
}
