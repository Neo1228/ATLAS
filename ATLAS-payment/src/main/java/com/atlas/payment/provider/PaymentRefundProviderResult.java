package com.atlas.payment.provider;

public record PaymentRefundProviderResult(Outcome outcome, String refundId, String providerStatus) {
    public PaymentRefundProviderResult {
        if (outcome == null) throw new IllegalArgumentException("Refund outcome is required");
    }

    public enum Outcome {
        COMPLETED,
        PENDING,
        FAILED,
        UNKNOWN
    }
}
