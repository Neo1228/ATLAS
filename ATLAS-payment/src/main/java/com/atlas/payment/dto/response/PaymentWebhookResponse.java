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
        boolean retryable) {
    public PaymentWebhookResponse(boolean duplicate, boolean accepted, UUID refundOperationId,
            PaymentRefundState refundStatus, UUID paymentAttemptId, boolean captureConfirmed) {
        this(duplicate, accepted, refundOperationId, refundStatus, paymentAttemptId, captureConfirmed, false);
    }
}
