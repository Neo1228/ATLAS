package com.atlas.payment.persistence.projection;

import com.atlas.payment.persistence.state.PaymentRecoveryOperationType;
import com.atlas.payment.persistence.state.PaymentRecoveryWorkStage;
import java.time.Instant;
import java.util.UUID;

public record ClaimedPaymentRecoveryJob(
        UUID recoveryJobId,
        PaymentRecoveryOperationType operationType,
        PaymentRecoveryWorkStage workStage,
        UUID operationId,
        int attemptCount,
        Instant firstEnqueuedAt,
        UUID leaseToken) {
}
