package com.atlas.payment.provider;

import com.atlas.payment.dto.request.PaymentRefundRequest;
import com.atlas.payment.dto.request.PaymentRequest;
import com.atlas.payment.dto.request.PaymentWebhookSignatureRequest;
import com.atlas.payment.dto.response.PaymentProviderResponse;
import com.atlas.payment.dto.response.PaymentRefundProviderResponse;
import com.atlas.payment.dto.response.PaymentWebhookEventResponse;

public interface PaymentProvider {
    PaymentProviderResponse perform(PaymentRequest request);

    PaymentProviderResponse completeApproval(PaymentRequest request, String returnedProviderOrderId);

    PaymentRefundProviderResponse refund(PaymentRefundRequest request);
    PaymentRefundProviderResponse getRefund(String refundId);
    default String providerCurrency() { return "KRW"; }
    default boolean requiresFxSnapshot() { return false; }
    PaymentWebhookEventResponse verifyWebhook(PaymentWebhookSignatureRequest signature, String rawBody);
}
