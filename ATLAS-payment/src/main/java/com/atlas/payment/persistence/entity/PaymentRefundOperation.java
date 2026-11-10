package com.atlas.payment.persistence.entity;

import com.atlas.payment.persistence.state.PaymentRefundState;
import java.time.Instant;
import java.util.UUID;

public record PaymentRefundOperation(
        UUID refundOperationId,
        UUID attemptId,
        String captureId,
        String idempotencyKey,
        long amountWon,
        String currency,
        UUID providerRequestId,
        String providerRefundId,
        String providerStatus,
        PaymentRefundState state,
        Instant createdAt,
        Instant updatedAt) {
}
