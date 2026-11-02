package com.atlas.admission;

import java.util.UUID;

public record JoinQueueCommand(
        UUID campaignId,
        String skuId,
        String buyerRef,
        String idempotencyKey) {
}
