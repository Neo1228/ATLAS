package com.atlas.payment.dto.response;

import com.atlas.payment.persistence.state.PaymentRecoveryOperationType;
import com.atlas.payment.persistence.state.PaymentRecoveryWorkStage;
import java.time.Instant;
import java.util.UUID;

public record PaymentRecoveryReviewItem(
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
        String lastErrorCode) {
}