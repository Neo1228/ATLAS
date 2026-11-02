package com.atlas.order.internal;

import com.atlas.order.OrderState;
import java.time.Instant;
import java.util.UUID;

record OrderRecord(
        UUID orderId,
        UUID campaignId,
        UUID ticketId,
        String buyerRef,
        String skuId,
        OrderState state,
        Instant checkoutExpiresAt,
        UUID paymentAttemptId) {
}
