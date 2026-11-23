package com.atlas.payment.dto.response;

public record PaymentProviderResponse(Outcome outcome, String orderId, String authorizationId,
        String captureId, String approvalUrl) {
    public PaymentProviderResponse {
        if (outcome == null) throw new IllegalArgumentException("Provider outcome is required");
    }

    public enum Outcome { AUTHORIZED, CAPTURED, DECLINED, PENDING, UNKNOWN, VOIDED }
}
