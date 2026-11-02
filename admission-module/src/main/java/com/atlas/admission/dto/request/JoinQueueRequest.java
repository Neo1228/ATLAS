package com.atlas.admission.dto.request;

import java.util.UUID;

public record JoinQueueRequest(
        UUID campaignId,
        String skuId,
        String buyerRef,
        String idempotencyKey) {
}
