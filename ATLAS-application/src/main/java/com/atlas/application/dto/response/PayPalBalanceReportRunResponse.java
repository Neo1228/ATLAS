package com.atlas.application.dto.response;

import java.time.Instant;
import java.util.UUID;

public record PayPalBalanceReportRunResponse(UUID runId, String reportSha256, Instant uploadedAt,
        String reportFormat, int rowCount, int matchedCount, int unmatchedCount, int mismatchCount,
        int duplicateCount) {
}
