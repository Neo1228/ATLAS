package com.atlas.settlement.service;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** 제한된 run 목록에서 사용하는 집계 요약입니다. */
public record PayPalBalanceReportRunSummary(UUID runId, String reportSha256, Instant uploadedAt,
        String reportFormat, int rowCount, int matchedCount, int unmatchedCount, int mismatchCount,
        int duplicateCount) {
    public PayPalBalanceReportRunSummary {
        Objects.requireNonNull(runId, "runId");
        Objects.requireNonNull(reportSha256, "reportSha256");
        Objects.requireNonNull(uploadedAt, "uploadedAt");
        Objects.requireNonNull(reportFormat, "reportFormat");
    }
}
