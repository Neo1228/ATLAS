package com.atlas.payment.provider.paypal.payouts;

import com.atlas.payment.dto.request.PayPalPayoutRequest;
import com.atlas.payment.dto.response.PayPalPayoutBatchResponse;

public interface PayPalPayoutsProvider {
    PayPalPayoutBatchResponse createBatch(PayPalPayoutRequest request);
    PayPalPayoutBatchResponse getBatch(String payoutBatchId);
}
