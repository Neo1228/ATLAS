package com.atlas.payment.persistence.projection;

public record WebhookRequeueRecord(String eventId, String actor, String reason, String outcome) { }
