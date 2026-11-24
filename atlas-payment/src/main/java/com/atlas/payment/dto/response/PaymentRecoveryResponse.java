package com.atlas.payment.dto.response;

import com.atlas.payment.model.PaymentRecoveryOperationType;
import com.atlas.payment.model.PaymentRecoveryWorkStage;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

public final class PaymentRecoveryResponse {
    private PaymentRecoveryResponse() {
    }

    public record Job(
            UUID recoveryJobId,
            PaymentRecoveryOperationType operationType,
            PaymentRecoveryWorkStage workStage,
            UUID operationId,
            int attemptCount,
            Instant firstEnqueuedAt,
            UUID leaseToken) {
    }


    /** 운영자 수동 재큐 결과. {@code duplicate}는 같은 멱등 키의 이전 요청을 재생한 경우다. */
    public record Requeue(
            UUID recoveryJobId,
            String status,
            int manualRequeueCount,
            Instant acceptedAt,
            boolean duplicate) {
    }

    public record ReviewItem(
            UUID recoveryJobId,
            PaymentRecoveryOperationType operationType,
            PaymentRecoveryWorkStage workStage,
            UUID operationId,
            UUID paymentAttemptId,
            UUID orderId,
            UUID checkoutId,
            int attemptCount,
            int manualRequeueCount,
            Instant firstEnqueuedAt,
            Instant lastAttemptAt,
            String lastErrorCode) {
    }
}
