package com.atlas.payment.dto.response;

import com.atlas.payment.persistence.state.PaymentRecoveryOperationType;
import java.time.Instant;
import java.util.UUID;

public record PaymentRecoveryJob(
        UUID recoveryJobId,
        PaymentRecoveryOperationType operationType,
        UUID operationId,
        int attemptCount,
        Instant firstEnqueuedAt,
        UUID leaseToken) {
}