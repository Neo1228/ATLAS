package com.atlas.payment.persistence.projection;

public record PayPalReportMatchFacts(String recordId, String matchKind, boolean confirmed, String captureId,
        Long providerAmountMinor, String providerCurrency) {
}
