package com.atlas.order.persistence.projection;

import java.util.List;
import java.util.UUID;

public record WorkflowLocationPlan(UUID fulfillmentLocationId, List<WorkflowItemRecord> items) {
}
