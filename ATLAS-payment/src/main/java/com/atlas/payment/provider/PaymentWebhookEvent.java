package com.atlas.payment.provider;

public record PaymentWebhookEvent(String eventId, String eventType, String resourceId, String resourceStatus) {
    public PaymentWebhookEvent {
        if (eventId == null || eventId.isBlank()) throw new IllegalArgumentException("Webhook event id is required");
        if (eventType == null || eventType.isBlank()) throw new IllegalArgumentException("Webhook event type is required");
    }
}
