package com.atlas.order.persistence.projection;

import com.atlas.order.model.LocationReservationState;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record ReservationWorkflowLocationSnapshot(UUID fulfillmentLocationId, String idempotencyKey, LocationReservationState state,
                               UUID reservationId, Instant remoteExpiresAt, String lastErrorCode,
                               Instant updatedAt, List<ReservationWorkflowItemRecord> items) {
}
