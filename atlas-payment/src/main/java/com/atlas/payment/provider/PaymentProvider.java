package com.atlas.payment.provider;

import com.atlas.payment.dto.request.PaymentRefundRequest;
import com.atlas.payment.dto.request.PaymentRequest;
import com.atlas.payment.dto.request.PaymentWebhookSignatureRequest;
import com.atlas.payment.dto.response.PaymentProviderResponse;
import com.atlas.payment.dto.response.PaymentRefundProviderResponse;
import com.atlas.payment.dto.response.PaymentWebhookEventResponse;
import java.time.Instant;

public interface PaymentProvider {
    PaymentProviderResponse perform(PaymentRequest request);

    PaymentProviderResponse completeApproval(PaymentRequest request, String returnedProviderOrderId);

    PaymentRefundProviderResponse refund(PaymentRefundRequest request);
    PaymentRefundProviderResponse getRefund(String refundId);
    /**
     * 첫 요청 시각이 이만큼 지나 같은 요청 id로 환불을 다시 보내지 않는가(ADR-0108). 그런 결과 불명 환불은 재시도로
     * 닫히지 않으므로 운영자가 provider에서 확인한 결과로 닫을 수 있는 기준이기도 하다. 기본은 기한 없음.
     */
    default boolean refundRetryExpired(Instant firstRequestedAt, Instant now) { return false; }
    default String providerCurrency() { return "KRW"; }
    default boolean requiresFxSnapshot() { return false; }
    PaymentWebhookEventResponse verifyWebhook(PaymentWebhookSignatureRequest signature, String rawBody);
}
