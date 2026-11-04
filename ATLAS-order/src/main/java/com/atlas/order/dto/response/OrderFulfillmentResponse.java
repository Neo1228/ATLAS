package com.atlas.order.dto.response;

import com.atlas.order.persistence.state.FulfillmentStatus;
import java.time.Instant;
import java.util.UUID;

public record OrderFulfillmentResponse(UUID orderId, FulfillmentStatus status, Instant createdAt,
                                       Instant updatedAt, Instant inProgressAt, Instant shippedAt,
                                       Instant deliveredAt) {
}
