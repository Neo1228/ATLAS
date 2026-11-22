package com.atlas.payment.persistence.projection;

import java.time.Instant;
import java.util.UUID;

public record PaymentRecoveryRequeueRow(
        UUID recoveryJobId,
        String status,
        int manualRequeueCount,
        Instant acceptedAt,
        boolean duplicate) {
}
