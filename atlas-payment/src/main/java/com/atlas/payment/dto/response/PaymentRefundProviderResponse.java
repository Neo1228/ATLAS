package com.atlas.payment.dto.response;

import java.math.BigDecimal;

public record PaymentRefundProviderResponse(
        Outcome outcome, String refundId, String providerStatus, BigDecimal amount, String currency) {
    public PaymentRefundProviderResponse(Outcome outcome, String refundId, String providerStatus) {
        this(outcome, refundId, providerStatus, null, null);
    }
    public PaymentRefundProviderResponse {
        if (outcome == null) throw new IllegalArgumentException("Refund outcome is required");
    }

    public enum Outcome {
        COMPLETED,
        PENDING,
        FAILED,
        UNKNOWN
    }
}
