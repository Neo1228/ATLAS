package com.atlas.order.persistence.projection;

import java.util.List;
import java.util.UUID;

public record LocationRestock(String restockKey, UUID fulfillmentLocationId, UUID refundId, UUID orderId,
                      UUID workflowId, UUID reservationId, int attemptCount, List<ReservationWorkflowItemRecord> items) {
}
