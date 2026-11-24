package com.atlas.settlement.model;

import java.math.BigDecimal;
import java.util.Objects;
import java.util.UUID;

/** 대사 분류를 포함하는 불변 PayPal report 행입니다. */
public record PayPalBalanceReportRow(UUID runId, int rowNumber, String recordId, String relatedRecordId,
        String originalRecordId, String recordType, String recordSubtype, String currency, BigDecimal grossAmount,
        BigDecimal feeAmount, BigDecimal netAmount, String transferId, PayPalBalanceReportRowStatus status, String reasonCode) {
    public PayPalBalanceReportRow {
        Objects.requireNonNull(runId, "runId");
        Objects.requireNonNull(recordId, "recordId");
        Objects.requireNonNull(recordType, "recordType");
        Objects.requireNonNull(status, "status");
        if (rowNumber < 1 || rowNumber > 10_000) {
            throw new IllegalArgumentException("rowNumber must be between 1 and 10000");
        }
        requireLength(recordId, 256, "recordId");
        requireLength(relatedRecordId, 256, "relatedRecordId");
        requireLength(originalRecordId, 256, "originalRecordId");
        requireLength(recordType, 64, "recordType");
        requireLength(recordSubtype, 64, "recordSubtype");
        requireLength(currency, 3, "currency");
        requireLength(transferId, 256, "transferId");
        requireLength(reasonCode, 64, "reasonCode");
        if (recordId.isBlank() || recordType.isBlank()) {
            throw new IllegalArgumentException("recordId and recordType must not be blank");
        }
    }

    private static void requireLength(String value, int maxLength, String field) {
        if (value != null && value.length() > maxLength) {
            throw new IllegalArgumentException(field + " exceeds " + maxLength + " characters");
        }
    }
}
