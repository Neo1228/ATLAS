package com.atlas.inventorylocation.persistence.projection;

import com.atlas.inventorylocation.model.LocationReservationStatus;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record LocationReservationRecord(UUID reservationId, UUID fulfillmentLocationId, UUID orderId, String idempotencyKey,
                                LocationReservationStatus status, Instant createdAt, Instant expiresAt, List<LocationReservationItemRecord> items) { }
