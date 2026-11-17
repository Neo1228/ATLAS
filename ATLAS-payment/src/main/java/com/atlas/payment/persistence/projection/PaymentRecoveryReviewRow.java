package com.atlas.payment.persistence.projection;

import com.atlas.payment.persistence.state.PaymentRecoveryOperationType;
import java.time.Instant;
import java.util.UUID;

public record PaymentRecoveryReviewRow(
        UUID recoveryJobId,
        PaymentRecoveryOperationType operationType,
        UUID operationId,
        UUID paymentAttemptId,
        UUID orderId,
        UUID checkoutId,
        int attemptCount,
        int manualRequeueCount,
        Instant firstEnqueuedAt,
        Instant lastAttemptAt,
        String lastErrorCode) {
}
