package com.atlas.payment.persistence.projection;

public record ClaimedRetry(WebhookRetryEvent event, int attempt, String leaseOwner) { }
