package com.atlas.payment.provider.paypal.payouts;

public interface PayPalPayoutsProvider {
    PayPalPayoutBatch createBatch(PayPalPayoutRequest request);
    PayPalPayoutBatch getBatch(String payoutBatchId);
}
