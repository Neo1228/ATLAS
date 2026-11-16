package com.atlas.application.dto.response;

public record PayPalWebhookRequeueResponse(String eventId, String outcome) { }
