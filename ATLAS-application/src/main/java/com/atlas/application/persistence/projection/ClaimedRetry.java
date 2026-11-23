package com.atlas.application.persistence.projection;

public record ClaimedRetry(WebhookRetryEvent event, int attempt, String leaseOwner) { }
