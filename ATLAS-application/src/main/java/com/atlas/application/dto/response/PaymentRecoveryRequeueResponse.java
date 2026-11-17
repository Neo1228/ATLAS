package com.atlas.application.dto.response;

import java.time.Instant;
import java.util.UUID;

public record PaymentRecoveryRequeueResponse(
        UUID recoveryJobId,
        String status,
        int manualRequeueCount,
        Instant acceptedAt,
        boolean duplicate) {
}
