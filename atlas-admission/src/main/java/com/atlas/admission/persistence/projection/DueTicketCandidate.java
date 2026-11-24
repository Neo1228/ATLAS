package com.atlas.admission.persistence.projection;

import java.time.Instant;
import java.util.UUID;

public record DueTicketCandidate(UUID ticketId, UUID sellerId, String skuId, Instant expiresAt) {
    public DueTicketCursor cursor() {
        return new DueTicketCursor(expiresAt, ticketId);
    }
}
