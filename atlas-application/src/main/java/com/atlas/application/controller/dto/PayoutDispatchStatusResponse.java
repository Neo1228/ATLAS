package com.atlas.application.controller.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/** PII-free operator read model for payout reconciliation and retry state. */
public record PayoutDispatchStatusResponse(
        UUID payoutId,
        String payoutState,
        String dispatchStatus,
        String senderBatchId,
        String providerBatchId,
        String providerItemId,
        String providerBatchStatus,
        String providerItemStatus,
        long requestedAmountWon,
        long providerAmountMinor,
        String payoutCurrency,
        BigDecimal fxRateWonPerUsd,
        String fxSource,
        LocalDate fxAsOfDate,
        int attemptCount,
        Instant firstEnqueuedAt,
        Instant lastAttemptAt,
        Instant nextAttemptAt,
        String lastErrorCode) {
}
