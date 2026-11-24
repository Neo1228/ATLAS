package com.atlas.order.persistence.projection;

import java.util.List;
import java.util.UUID;

public record ReservationWorkflowLocationPlan(UUID fulfillmentLocationId, List<ReservationWorkflowItemRecord> items) {
}
