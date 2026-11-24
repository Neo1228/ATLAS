package com.atlas.order.persistence.projection;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record ReservationWorkflowLocationSnapshot(UUID fulfillmentLocationId, String idempotencyKey, String state,
                               UUID reservationId, Instant remoteExpiresAt, String lastErrorCode,
                               Instant updatedAt, List<ReservationWorkflowItemRecord> items) {
}
