package com.atlas.application.dto.response;

import java.math.BigDecimal;
import java.util.List;

public record PayPalBalanceReportReconciliationResponse(
        String reportName,
        String reportSha256,
        String bankReceiptEvidence,
        String providerIdMappingStatus,
        Summary summary,
        List<RowResult> rows) {

    public PayPalBalanceReportReconciliationResponse {
        rows = List.copyOf(rows);
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