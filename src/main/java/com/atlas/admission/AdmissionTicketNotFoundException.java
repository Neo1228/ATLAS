package com.atlas.admission;

import java.util.UUID;

public final class AdmissionTicketNotFoundException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public AdmissionTicketNotFoundException(UUID campaignId, UUID ticketId) {
        super("Unknown ticket: " + campaignId + "/" + ticketId);
    }
}
