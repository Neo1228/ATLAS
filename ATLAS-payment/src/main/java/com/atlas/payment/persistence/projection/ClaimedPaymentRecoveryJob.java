package com.atlas.payment.persistence.projection;

import com.atlas.payment.persistence.state.PaymentRecoveryOperationType;
import java.time.Instant;
import java.util.UUID;

public record ClaimedPaymentRecoveryJob(
        UUID recoveryJobId,
        PaymentRecoveryOperationType operationType,
        UUID operationId,
        int attemptCount,
        Instant firstEnqueuedAt,
        UUID leaseToken) {
}
