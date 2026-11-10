package com.atlas.payment.dto.response;

import com.atlas.payment.persistence.state.PaymentRefundState;
import java.util.UUID;

public record PaymentWebhookResult(
        boolean duplicate,
        boolean accepted,
        UUID refundOperationId,
        PaymentRefundState refundStatus,
        UUID paymentAttemptId,
        boolean captureConfirmed) {
}
