package com.atlas.payment.dto.response;

import java.math.BigDecimal;

/**
 * 검증된 PayPal 웹훅에서 처리에 쓰는 사실.
 *
 * <p>분쟁 결과({@code dispute_outcome.outcome_code}, {@code dispute_outcome.amount_refunded})는 분쟁 사건에만 있다
 * (customer_disputes_v1). 다른 사건은 셋 다 null 이다.
 */
public record PaymentWebhookEventResponse(
        String eventId,
        String eventType,
        String resourceId,
        String resourceStatus,
        String providerOrderId,
        String relatedCaptureId,
        BigDecimal resourceAmount,
        String resourceCurrency,
        String disputeOutcomeCode,
        BigDecimal disputeRefundedAmount,
        String disputeRefundedCurrency) {
    public PaymentWebhookEventResponse {
        if (eventId == null || eventId.isBlank()) throw new IllegalArgumentException("Webhook event id is required");
        if (eventType == null || eventType.isBlank()) throw new IllegalArgumentException("Webhook event type is required");
    }

    public PaymentWebhookEventResponse(String eventId, String eventType, String resourceId, String resourceStatus,
            String providerOrderId, String relatedCaptureId, BigDecimal resourceAmount, String resourceCurrency) {
        this(eventId, eventType, resourceId, resourceStatus, providerOrderId, relatedCaptureId, resourceAmount,
                resourceCurrency, null, null, null);
    }
}
