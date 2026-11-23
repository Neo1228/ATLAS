package com.atlas.order.persistence.projection;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record WorkflowSnapshot(UUID workflowId, UUID orderId, String idempotencyKey, String state,
                               int attemptCount, Instant nextAttemptAt, Instant createdAt,
                               Instant updatedAt, String lastErrorCode, List<WorkflowLocationSnapshot> locations) {
}
