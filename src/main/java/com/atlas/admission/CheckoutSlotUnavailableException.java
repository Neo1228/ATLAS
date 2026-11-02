package com.atlas.admission;

import java.util.UUID;

public final class CheckoutSlotUnavailableException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public CheckoutSlotUnavailableException(UUID campaignId, UUID ticketId) {
        super("Ticket does not hold an available checkout slot: " + campaignId + "/" + ticketId);
    }
}
