package com.atlas.inventory.location.persistence.projection;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record LocationReservationRecord(UUID reservationId, UUID fulfillmentLocationId, UUID orderId, String idempotencyKey,
                                String status, Instant createdAt, Instant expiresAt, List<LocationReservationItemRecord> items) { }
