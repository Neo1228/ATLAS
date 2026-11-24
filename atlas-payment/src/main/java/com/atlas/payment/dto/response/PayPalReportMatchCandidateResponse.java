package com.atlas.payment.dto.response;

public record PayPalReportMatchCandidateResponse(String recordId, PayPalReportMatchKind kind, boolean confirmed, String captureId,
        Long providerAmountMinor, String providerCurrency) {
}
