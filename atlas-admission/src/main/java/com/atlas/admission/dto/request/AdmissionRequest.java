package com.atlas.admission.dto.request;

import java.time.Duration;
import java.util.UUID;

public final class AdmissionRequest {
    private AdmissionRequest() {
    }

    public record ConfigureSellerSkuPolicy(
            UUID sellerId,
            String skuId,
            int maxActiveCheckouts) {
    }

    public record CreateCampaign(UUID sellerId, String skuId, Duration queueTicketTtl) {
    }

    public record JoinQueue(
            UUID campaignId,
            String skuId,
            String buyerRef,
            String idempotencyKey) {
    }
}
