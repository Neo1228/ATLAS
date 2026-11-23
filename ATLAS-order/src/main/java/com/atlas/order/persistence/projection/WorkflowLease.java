package com.atlas.order.persistence.projection;

import java.time.Instant;
import java.util.UUID;

public record WorkflowLease(UUID workflowId, UUID leaseToken, Instant leaseUntil) {
}
