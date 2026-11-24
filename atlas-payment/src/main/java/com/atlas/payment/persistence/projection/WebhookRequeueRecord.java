package com.atlas.payment.persistence.projection;

import com.atlas.payment.model.WebhookRequeueOutcome;

public record WebhookRequeueRecord(String eventId, String actor, String reason, WebhookRequeueOutcome outcome) { }
