package com.atlas.payment.persistence.projection;

public record ClaimedWebhookRetry(WebhookRetryEvent event, int attempt, String leaseOwner) { }
