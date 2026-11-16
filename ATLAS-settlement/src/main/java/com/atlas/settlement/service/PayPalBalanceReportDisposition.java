package com.atlas.settlement.service;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** PayPal 대사 행에 연결된 불변 disposition 감사 기록입니다. */
public record PayPalBalanceReportDisposition(UUID dispositionId, UUID runId, int rowNumber, String disposition,
        String actor, String reason, String idempotencyKey, Instant createdAt) {
    public PayPalBalanceReportDisposition {
        Objects.requireNonNull(dispositionId, "dispositionId");
        Objects.requireNonNull(runId, "runId");
        Objects.requireNonNull(disposition, "disposition");
        Objects.requireNonNull(actor, "actor");
        Objects.requireNonNull(reason, "reason");
        Objects.requireNonNull(idempotencyKey, "idempotencyKey");
        Objects.requireNonNull(createdAt, "createdAt");
        if (rowNumber < 1 || rowNumber > 10_000) {
            throw new IllegalArgumentException("rowNumber must be between 1 and 10000");
        }
        if (!disposition.equals("CONFIRMED_EXTERNAL") && !disposition.equals("DEFERRED")
                && !disposition.equals("NEEDS_REVIEW")) {
            throw new IllegalArgumentException("Unsupported disposition: " + disposition);
        }
        if (actor.length() > 128 || reason.trim().isEmpty() || reason.length() > 500
                || idempotencyKey.length() > 128) {
            throw new IllegalArgumentException("actor, reason or idempotencyKey exceeds the V34 constraints");
        }
    }
}
