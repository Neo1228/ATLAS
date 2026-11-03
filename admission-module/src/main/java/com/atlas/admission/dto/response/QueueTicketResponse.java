package com.atlas.admission.dto.response;

import com.atlas.admission.persistence.state.TicketStatus;

import java.time.Instant;
import java.util.UUID;

public record QueueTicketResponse(
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
