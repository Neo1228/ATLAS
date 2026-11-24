package com.atlas.admission.persistence.projection;

import java.time.Instant;
import java.util.UUID;

public record DueTicketCursor(Instant expiresAt, UUID ticketId) { }
