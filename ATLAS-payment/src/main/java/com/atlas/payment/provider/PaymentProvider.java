package com.atlas.payment.provider;

public interface PaymentProvider {
    PaymentProviderResult perform(PaymentRequest request);

    PaymentProviderResult completeApproval(PaymentRequest request, String returnedProviderOrderId);

    PaymentRefundProviderResult refund(PaymentRefundRequest request);
    PaymentRefundProviderResult getRefund(String refundId);
    default String providerCurrency() { return "KRW"; }
    default boolean requiresFxSnapshot() { return false; }
    PaymentWebhookEvent verifyWebhook(PaymentWebhookSignature signature, String rawBody);
}
