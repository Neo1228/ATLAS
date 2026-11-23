package com.atlas.payment.provider;

import com.atlas.payment.dto.request.PaymentRefundRequest;
import com.atlas.payment.dto.request.PaymentRequest;
import com.atlas.payment.dto.request.PaymentWebhookSignatureRequest;
import com.atlas.payment.dto.response.PaymentProviderResponse;
import com.atlas.payment.dto.response.PaymentRefundProviderResponse;
import com.atlas.payment.dto.response.PaymentWebhookEventResponse;
import com.atlas.payment.exception.PaymentException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "atlas.payment.provider", havingValue = "disabled", matchIfMissing = true)
public class DisabledPaymentProvider implements PaymentProvider {
    @Override
    public PaymentProviderResponse perform(PaymentRequest request) {
        throw new PaymentException(PaymentException.Code.PAYMENT_PROVIDER_DISABLED,
                "No payment provider is configured");
    }

    @Override
    public PaymentProviderResponse completeApproval(PaymentRequest request, String returnedProviderOrderId) {
        throw unavailable();
    }
    @Override
    public PaymentRefundProviderResponse refund(PaymentRefundRequest request) {
        throw unavailable();
    }

    @Override
    public PaymentRefundProviderResponse getRefund(String refundId) {
        throw unavailable();
    }

    @Override
    public PaymentWebhookEventResponse verifyWebhook(PaymentWebhookSignatureRequest signature, String rawBody) {
        throw unavailable();
    }

    private PaymentException unavailable() {
        return new PaymentException(PaymentException.Code.PAYMENT_PROVIDER_DISABLED,
                "No payment provider is configured");
    }
}
