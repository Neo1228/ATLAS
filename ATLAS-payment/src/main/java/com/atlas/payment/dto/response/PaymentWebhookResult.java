package com.atlas.payment.dto.response;

import com.atlas.payment.persistence.state.PaymentRefundState;
import java.util.UUID;

public record PaymentWebhookResult(
        boolean duplicate,
        boolean accepted,
        UUID refundOperationId,
        PaymentRefundState refundStatus,
        UUID paymentAttemptId,
        boolean captureConfirmed,
        boolean retryable) {
    public PaymentWebhookResult(boolean duplicate, boolean accepted, UUID refundOperationId,
            PaymentRefundState refundStatus, UUID paymentAttemptId, boolean captureConfirmed) {
        this(duplicate, accepted, refundOperationId, refundStatus, paymentAttemptId, captureConfirmed, false);
    }
}
