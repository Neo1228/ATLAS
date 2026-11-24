package com.atlas.admission.dto.response;

import com.atlas.admission.model.CampaignStatus;
import com.atlas.admission.model.TicketStatus;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

public final class AdmissionResponse {
    private AdmissionResponse() {
    }

    public record Campaign(UUID campaignId, UUID sellerId, String skuId, CampaignStatus status,
            Duration queueTicketTtl) {
    }

    public record QueueTicket(
            UUID ticketId,
            UUID campaignId,
            UUID sellerId,
            String skuId,
            String buyerRef,
            long sequence,
            TicketStatus status,
            Instant expiresAt,
            boolean checkoutSlotHeld) {
    }
}
