package com.atlas.application.dto.response;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public final class SettlementHttpResponse {
    private SettlementHttpResponse() {
    }

    public record PayoutDispatchReviewPage(int limit, int offset, boolean hasMore,
            List<PayoutDispatchStatus> items) {
        public PayoutDispatchReviewPage {
            items = List.copyOf(items);
        }
    }

    /** PII-free operator read model for payout reconciliation and retry state. */
    public record PayoutDispatchStatus(
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

    /** Seller payout eligibility profile; recipient email is intentionally never returned. */
    public record SellerPayoutProfile(
            UUID sellerId,
            String countryCode,
            String payoutCurrency,
            String status,
            int profileVersion,
            Instant updatedAt,
            Instant verifiedAt) {
    }

    public record PayPalBalanceReportReconciliation(
            UUID runId,
            Instant uploadedAt,
            boolean duplicateUpload,
            String reportName,
            String reportSha256,
            String bankReceiptEvidence,
            String providerIdMappingStatus,
            Summary summary,
            List<RowResult> rows) {

        public PayPalBalanceReportReconciliation {
            rows = List.copyOf(rows);
        }

        public PayPalBalanceReportReconciliation(
                String reportName,
                String reportSha256,
                String bankReceiptEvidence,
                String providerIdMappingStatus,
                Summary summary,
                List<RowResult> rows) {
            this(null, null, false, reportName, reportSha256, bankReceiptEvidence,
                    providerIdMappingStatus, summary, rows);
        }

        public record Summary(int totalRows, int matched, int unmatched, int duplicate, int mismatch) {
        }

        public record RowResult(
                int rowNumber,
                String recordId,
                String relatedRecordId,
                String originalRecordId,
                String recordType,
                String recordSubtype,
                String currency,
                BigDecimal grossAmount,
                BigDecimal feeAmount,
                BigDecimal netAmount,
                String transferId,
                Status status,
                String reasonCode) {
        }

        public enum Status {
            MATCHED,
            UNMATCHED,
            DUPLICATE,
            MISMATCH
        }
    }
}
