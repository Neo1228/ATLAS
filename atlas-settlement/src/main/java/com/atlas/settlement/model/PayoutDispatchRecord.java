package com.atlas.settlement.model;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/** Snapshot leased to a worker. Recipient ciphertext is never suitable for logs. */
public record PayoutDispatchRecord(
        UUID payoutId, int profileVersion, String recipientEmailCiphertext, String recipientCountryCode,
        String payoutCurrency, long requestedAmountWon, long providerAmountMinor,
        BigDecimal fxRateWonPerUsd, String fxSource, LocalDate fxAsOfDate,
        long providerDebitBookValueWon, long payoutFxRoundingExpenseWon,
        Long providerFeeMinor, String providerFeeCurrency, Long providerFeeWon,
        String senderBatchId, String senderItemId, String paypalRequestId, String requestPayloadSha256,
        String providerBatchId, String providerItemId, String providerBatchStatus, String providerItemStatus,
        String status, Instant availableAt, Instant firstEnqueuedAt, int attemptCount,
        Instant lastAttemptAt, String lastErrorCode, UUID leaseToken, Instant leaseUntil) {
    @Override
    public String toString() {
        return "PayoutDispatchRecord[payoutId=" + payoutId + ", status=" + status
                + ", attemptCount=" + attemptCount + ", recipientEmailCiphertext=[REDACTED]"
                + ", providerBatchId=[REDACTED], providerItemId=[REDACTED]]";
    }
}
