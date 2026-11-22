package com.atlas.payment.provider.paypal.payouts;

import java.util.Locale;

public record PayPalPayoutRequest(
        String senderBatchId,
        String senderItemId,
        String requestId,
        String recipientEmail,
        long amountMinor,
        String currency) {
    public PayPalPayoutRequest {
        if (blank(senderBatchId) || blank(senderItemId) || blank(requestId) || blank(recipientEmail)) {
            throw new IllegalArgumentException("PayPal payout identifiers and recipient are required");
        }
        if (amountMinor <= 0) throw new IllegalArgumentException("amountMinor must be positive");
        if (!"USD".equals(currency == null ? null : currency.toUpperCase(Locale.ROOT))) {
            throw new IllegalArgumentException("PayPal Payouts supports USD only");
        }
        currency = "USD";
    }

    @Override
    public String toString() {
        return "PayPalPayoutRequest[senderBatchId=" + senderBatchId + ", senderItemId=" + senderItemId
                + ", requestId=" + requestId + ", recipientEmail=[REDACTED], amountMinor=" + amountMinor
                + ", currency=" + currency + "]";
    }

    private static boolean blank(String value) { return value == null || value.isBlank(); }
}
