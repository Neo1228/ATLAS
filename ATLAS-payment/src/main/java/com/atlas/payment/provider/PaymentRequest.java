package com.atlas.payment.provider;

import java.util.UUID;

public record PaymentRequest(PaymentOperation operation, UUID ownerId, UUID attemptId,
        long providerAmountMinor, String providerCurrency, String providerOrderId, String providerAuthorizationId,
        String createOrderRequestId, String authorizationRequestId, String captureRequestId,
        String voidRequestId, String returnUrl, String cancelUrl) {
    public PaymentRequest {
        if (operation == null || ownerId == null || attemptId == null || providerAmountMinor <= 0
                || providerCurrency == null || providerCurrency.isBlank()) {
            throw new IllegalArgumentException("A positive server-derived payment amount is required");
        }
    }
}
