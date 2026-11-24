package com.atlas.payment.dto.response;

public record PayPalWebhookRequeueResponse(String eventId, String outcome) { }
