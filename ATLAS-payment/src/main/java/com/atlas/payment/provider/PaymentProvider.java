package com.atlas.payment.provider;

public interface PaymentProvider {
    PaymentProviderResult perform(PaymentRequest request);

    PaymentProviderResult completeApproval(PaymentRequest request, String returnedProviderOrderId);

    PaymentRefundProviderResult refund(PaymentRefundRequest request);
    PaymentRefundProviderResult getRefund(String refundId);
    PaymentWebhookEvent verifyWebhook(PaymentWebhookSignature signature, String rawBody);
}
