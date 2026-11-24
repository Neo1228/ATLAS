package com.atlas.payment.dto.response;

public final class PayPalReportMatchResponse {
    private PayPalReportMatchResponse() {
    }

    public record Candidate(String recordId, Kind kind, boolean confirmed, String captureId,
            Long providerAmountMinor, String providerCurrency) {
    }

    public enum Kind {
        CAPTURE,
        REFUND
    }
}
