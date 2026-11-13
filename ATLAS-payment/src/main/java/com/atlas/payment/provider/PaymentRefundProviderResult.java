package com.atlas.payment.provider;

import java.math.BigDecimal;

public record PaymentRefundProviderResult(
        Outcome outcome, String refundId, String providerStatus, BigDecimal amount, String currency) {
    public PaymentRefundProviderResult(Outcome outcome, String refundId, String providerStatus) {
        this(outcome, refundId, providerStatus, null, null);
    }
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
