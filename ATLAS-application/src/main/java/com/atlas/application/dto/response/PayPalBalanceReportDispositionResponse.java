package com.atlas.application.dto.response;

import java.time.Instant;
import java.util.UUID;

public record PayPalBalanceReportDispositionResponse(UUID dispositionId, UUID runId, int rowNumber,
        String disposition, String actor, String reason, Instant createdAt) {
}
