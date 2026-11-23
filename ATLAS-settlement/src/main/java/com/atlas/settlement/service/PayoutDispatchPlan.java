package com.atlas.settlement.service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Objects;
import java.util.UUID;

/** Immutable approval-time facts required to dispatch one payout. */
public record PayoutDispatchPlan(
        UUID payoutId,
        int profileVersion,
        String recipientEmailCiphertext,
        String recipientCountryCode,
        long requestedAmountWon,
        long providerAmountMinor,
        BigDecimal fxRateWonPerUsd,
        String fxSource,
        LocalDate fxAsOfDate,
        long providerDebitBookValueWon,
        long payoutFxRoundingExpenseWon,
        String senderBatchId,
        String senderItemId,
        String paypalRequestId,
        String requestPayloadSha256) {
    public PayoutDispatchPlan {
        Objects.requireNonNull(payoutId);
        Objects.requireNonNull(recipientEmailCiphertext);
        Objects.requireNonNull(recipientCountryCode);
        Objects.requireNonNull(fxRateWonPerUsd);
        Objects.requireNonNull(fxSource);
        Objects.requireNonNull(fxAsOfDate);
        Objects.requireNonNull(senderBatchId);
        Objects.requireNonNull(senderItemId);
        Objects.requireNonNull(paypalRequestId);
        Objects.requireNonNull(requestPayloadSha256);
        if (profileVersion <= 0 || requestedAmountWon <= 0 || providerAmountMinor <= 0
                || fxRateWonPerUsd.signum() <= 0 || !recipientEmailCiphertext.startsWith("v2:")
                || recipientEmailCiphertext.contains("@") || !recipientCountryCode.matches("[A-Z]{2}")
                || !fxSource.matches("[A-Za-z0-9_-]{1,32}") || !validId(senderBatchId)
                || !validId(senderItemId) || !validId(paypalRequestId)
                || !requestPayloadSha256.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("Invalid immutable payout dispatch facts");
        }
        long expectedRounding;
        try {
            expectedRounding = Math.subtractExact(providerDebitBookValueWon, requestedAmountWon);
        } catch (ArithmeticException overflow) {
            throw new IllegalArgumentException("Invalid payout book value", overflow);
        }
        if (expectedRounding < 0 || payoutFxRoundingExpenseWon != expectedRounding) {
            throw new IllegalArgumentException("Invalid immutable payout dispatch amounts");
        }
    }

    private static boolean validId(String value) {
        return value.matches("[A-Za-z0-9_-]{1,128}");
    }
    @Override
    public String toString() {
        return "PayoutDispatchPlan[payoutId=" + payoutId + ", profileVersion=" + profileVersion
                + ", amountWon=" + requestedAmountWon + ", providerAmountMinor=" + providerAmountMinor
                + ", recipientEmailCiphertext=[REDACTED], senderBatchId=[REDACTED]]";
    }
}
