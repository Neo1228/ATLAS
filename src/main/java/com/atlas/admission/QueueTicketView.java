package com.atlas.admission;

import java.time.Instant;
import java.util.UUID;

public record QueueTicketView(
        UUID ticketId,
        UUID campaignId,
        String skuId,
        String buyerRef,
        long sequence,
        TicketStatus status,
        Instant expiresAt,
        boolean checkoutSlotHeld) {
}
