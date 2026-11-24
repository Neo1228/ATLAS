package com.atlas.payment.persistence.projection;

import com.atlas.payment.model.PaymentRecoveryJobStatus;
import java.time.Instant;
import java.util.UUID;

public record PaymentRecoveryRequeueRow(
        UUID recoveryJobId,
        PaymentRecoveryJobStatus status,
        int manualRequeueCount,
        Instant acceptedAt,
        boolean duplicate) {
}
