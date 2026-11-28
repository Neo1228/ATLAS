package com.atlas.payment.dto.request;

import java.time.Instant;

/** {@code firstRequestedAt}은 같은 {@code requestId}로 처음 요청한 시각(환불 작업 생성 시각)이다. */
public record PaymentRefundRequest(String captureId, long providerAmountMinor, String providerCurrency,
        String requestId, Instant firstRequestedAt) {
    public PaymentRefundRequest {
        if (captureId == null || captureId.isBlank()) throw new IllegalArgumentException("Capture id is required");
        if (providerAmountMinor <= 0) throw new IllegalArgumentException("Refund amount must be positive");
        if (providerCurrency == null || providerCurrency.isBlank()) {
            throw new IllegalArgumentException("Provider currency is required");
        }
        if (requestId == null || requestId.isBlank()) throw new IllegalArgumentException("Request id is required");
        if (firstRequestedAt == null) throw new IllegalArgumentException("First request time is required");
    }
}
