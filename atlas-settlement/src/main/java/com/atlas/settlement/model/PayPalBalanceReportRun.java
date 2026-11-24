package com.atlas.settlement.model;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** 가져온 PayPal 대사 report 한 건의 불변 요약입니다. */
public record PayPalBalanceReportRun(UUID runId, String reportSha256, Instant uploadedAt, String reportFormat,
        int rowCount, int matchedCount, int unmatchedCount, int mismatchCount, int duplicateCount) {
    public PayPalBalanceReportRun {
        Objects.requireNonNull(runId, "runId");
        Objects.requireNonNull(reportSha256, "reportSha256");
        Objects.requireNonNull(uploadedAt, "uploadedAt");
        Objects.requireNonNull(reportFormat, "reportFormat");
        if (!reportSha256.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("reportSha256 must be 64 lowercase hexadecimal characters");
        }
        if (reportFormat.isBlank() || reportFormat.length() > 16) {
            throw new IllegalArgumentException("reportFormat must contain 1 to 16 characters");
        }
        if (rowCount < 0 || rowCount > 10_000 || matchedCount < 0 || unmatchedCount < 0
                || mismatchCount < 0 || duplicateCount < 0
                || matchedCount + unmatchedCount + mismatchCount + duplicateCount != rowCount) {
            throw new IllegalArgumentException("rowCount must equal the nonnegative outcome counts and be at most 10000");
        }
    }
}
