package com.atlas.payment.dto.response;

import java.math.BigDecimal;

public record PaymentWebhookEventResponse(
        String eventId,
        String eventType,
        String resourceId,
        String resourceStatus,
        String providerOrderId,
        String relatedCaptureId,
        BigDecimal resourceAmount,
        String resourceCurrency) {
    public PaymentWebhookEventResponse {
        if (eventId == null || eventId.isBlank()) throw new IllegalArgumentException("Webhook event id is required");
        if (eventType == null || eventType.isBlank()) throw new IllegalArgumentException("Webhook event type is required");
    }
}
