package com.atlas.payment.provider;

public record PaymentRefundRequest(String captureId, long providerAmountMinor, String providerCurrency,
        String requestId) {
    public PaymentRefundRequest {
        if (captureId == null || captureId.isBlank()) throw new IllegalArgumentException("Capture id is required");
        if (providerAmountMinor <= 0) throw new IllegalArgumentException("Refund amount must be positive");
        if (providerCurrency == null || providerCurrency.isBlank()) {
            throw new IllegalArgumentException("Provider currency is required");
        }
        if (requestId == null || requestId.isBlank()) throw new IllegalArgumentException("Request id is required");
    }
}
