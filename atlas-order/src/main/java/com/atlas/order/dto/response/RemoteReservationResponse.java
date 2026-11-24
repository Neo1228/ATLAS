package com.atlas.order.dto.response;

import java.time.Instant;
import java.util.UUID;

public record RemoteReservationResponse(UUID reservationId, String status, Instant expiresAt) {
}
