package com.atlas.payment.dto.response;

import com.atlas.payment.persistence.state.PaymentRefundState;
import java.time.Instant;
import java.util.UUID;

public record PaymentRefundResponse(UUID refundOperationId, UUID attemptId, String captureId,
        long amountWon, String currency, String providerRefundId, PaymentRefundState status, Instant updatedAt) {
}
