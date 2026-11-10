package com.atlas.payment.provider;

public record PaymentRefundRequest(String captureId, long amountMinor, String currency, String requestId) {
    public PaymentRefundRequest {
        if (captureId == null || captureId.isBlank()) throw new IllegalArgumentException("Capture id is required");
        if (amountMinor <= 0) throw new IllegalArgumentException("Refund amount must be positive");
        if (!"KRW".equals(currency)) throw new IllegalArgumentException("Only KRW refunds are supported");
        if (requestId == null || requestId.isBlank()) throw new IllegalArgumentException("Request id is required");
    }
}
