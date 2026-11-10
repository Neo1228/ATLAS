package com.atlas.payment.provider;

import com.atlas.payment.exception.PaymentException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "atlas.payment.provider", havingValue = "disabled", matchIfMissing = true)
public class DisabledPaymentProvider implements PaymentProvider {
    @Override
    public PaymentProviderResult perform(PaymentRequest request) {
        throw new PaymentException(PaymentException.Code.PAYMENT_PROVIDER_DISABLED,
                "No payment provider is configured");
    }

    @Override
    public PaymentProviderResult completeApproval(PaymentRequest request, String returnedProviderOrderId) {
        throw unavailable();
    }
    @Override
    public PaymentRefundProviderResult refund(PaymentRefundRequest request) {
        throw unavailable();
    }

    @Override
    public PaymentRefundProviderResult getRefund(String refundId) {
        throw unavailable();
    }

    @Override
    public PaymentWebhookEvent verifyWebhook(PaymentWebhookSignature signature, String rawBody) {
        throw unavailable();
    }

    private PaymentException unavailable() {
        return new PaymentException(PaymentException.Code.PAYMENT_PROVIDER_DISABLED,
                "No payment provider is configured");
    }
}
