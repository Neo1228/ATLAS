package com.atlas.payment.dto.response;

import com.atlas.payment.persistence.state.PaymentRecoveryOperationType;
import com.atlas.payment.persistence.state.PaymentRecoveryWorkStage;
import java.time.Instant;
import java.util.UUID;

public record PaymentRecoveryJob(
        UUID recoveryJobId,
        PaymentRecoveryOperationType operationType,
        PaymentRecoveryWorkStage workStage,
        UUID operationId,
        int attemptCount,
        Instant firstEnqueuedAt,
        UUID leaseToken) {
}