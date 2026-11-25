package com.atlas.payment.persistence.projection;

import com.atlas.payment.model.PaymentRecoveryOperationType;
import com.atlas.payment.model.PaymentRecoveryWorkStage;
import java.time.Instant;
import java.util.UUID;

public record PaymentRecoveryReviewRow(
        UUID recoveryJobId,
        PaymentRecoveryOperationType operationType,
        PaymentRecoveryWorkStage workStage,
        UUID operationId,
        UUID paymentAttemptId,
        UUID orderId,
        UUID checkoutId,
        int attemptCount,
        int manualRequeueCount,
        Instant firstEnqueuedAt,
        Instant lastAttemptAt,
        String lastErrorCode,
        Instant authorizationExpiresAt) {
}
