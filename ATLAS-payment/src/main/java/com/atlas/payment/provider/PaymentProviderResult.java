package com.atlas.payment.provider;

public record PaymentProviderResult(Outcome outcome, String orderId, String authorizationId,
        String captureId, String approvalUrl) {
    public PaymentProviderResult {
        if (outcome == null) throw new IllegalArgumentException("Provider outcome is required");
    }

    public enum Outcome { AUTHORIZED, CAPTURED, DECLINED, PENDING, UNKNOWN, VOIDED }
}
