package com.atlas.payment.provider.paypal.payouts;

import java.math.BigDecimal;
import java.util.List;

public record PayPalPayoutBatch(
        String batchId,
        String senderBatchId,
        String batchStatus,
        BigDecimal amount,
        String currency,
        BigDecimal fees,
        String feeCurrency,
        List<Item> items) {
    public PayPalPayoutBatch { items = items == null ? List.of() : List.copyOf(items); }

    public record Item(
            String itemId,
            String senderItemId,
            String transactionStatus,
            BigDecimal amount,
            String currency,
            BigDecimal fee,
            String feeCurrency,
            BigDecimal conversionFromAmount,
            String conversionFromCurrency,
            BigDecimal conversionToAmount,
            String conversionToCurrency,
            BigDecimal conversionRate,
            boolean currencyConversionPresent) {
        public boolean isFinalSuccess() { return "SUCCESS".equalsIgnoreCase(transactionStatus); }
        public boolean isUnclaimed() { return "UNCLAIMED".equalsIgnoreCase(transactionStatus); }
    }
}
