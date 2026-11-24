package com.atlas.payment.persistence.projection;

import java.util.List;

public record WebhookRetryPollResult(List<ClaimedWebhookRetry> claimed, int reapedCount) { }
