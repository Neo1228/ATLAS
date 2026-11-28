package com.atlas.payment.dto.response;

import com.atlas.payment.model.PaymentRefundState;
import java.util.UUID;

public record PaymentWebhookResponse(
        boolean duplicate,
        boolean accepted,
        UUID refundOperationId,
        PaymentRefundState refundStatus,
        UUID paymentAttemptId,
        boolean captureConfirmed,
        boolean retryable,
        UUID providerAdjustmentId) {
    public PaymentWebhookResponse(boolean duplicate, boolean accepted, UUID refundOperationId,
            PaymentRefundState refundStatus, UUID paymentAttemptId, boolean captureConfirmed, boolean retryable) {
        this(duplicate, accepted, refundOperationId, refundStatus, paymentAttemptId, captureConfirmed, retryable, null);
    }

    /** PayPal 쪽에서 시작된 환불·역전을 기록했다. 호출자가 판매자 원장에 반영해야 한다(ADR-0111). */
    public static PaymentWebhookResponse providerAdjustment(UUID adjustmentId) {
        return new PaymentWebhookResponse(false, true, null, null, null, false, false, adjustmentId);
    }

    public PaymentWebhookResponse(boolean duplicate, boolean accepted, UUID refundOperationId,
            PaymentRefundState refundStatus, UUID paymentAttemptId, boolean captureConfirmed) {
        this(duplicate, accepted, refundOperationId, refundStatus, paymentAttemptId, captureConfirmed, false);
    }
}
