package com.atlas.payment.provider;

import java.math.BigDecimal;

public record PaymentWebhookEvent(
        String eventId,
        String eventType,
        String resourceId,
        String resourceStatus,
        String providerOrderId,
        String relatedCaptureId,
        BigDecimal resourceAmount,
        String resourceCurrency) {
    public PaymentWebhookEvent {
        if (eventId == null || eventId.isBlank()) throw new IllegalArgumentException("Webhook event id is required");
        if (eventType == null || eventType.isBlank()) throw new IllegalArgumentException("Webhook event type is required");
    }
}
