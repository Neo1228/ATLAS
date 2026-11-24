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

    /**
     * 복구 큐 메트릭의 service 계약 타입. Prometheus Gauge 가 읽는다.
     *
     * <p>repository 의 projection 을 service 인터페이스에 그대로 노출하면 계층 규칙을 위반하므로,
     * service 가 이 응답 타입으로 변환해 돌려준다.
     */
    public record Metrics(
            Map<String, Long> statusCounts,
            Map<StageKey, StageMetrics> stages) {

        public long statusCount(String status) {
            Long value = statusCounts.get(status);
            return value == null ? 0L : value;
        }

        public long stageCount(String stage, String status) {
            StageMetrics metrics = stages.get(new StageKey(stage, status));
            return metrics == null ? 0L : metrics.total();
        }

        public double stageOldestDueAgeSeconds(String stage) {
            double oldest = 0;
            for (Map.Entry<StageKey, StageMetrics> entry : stages.entrySet()) {
                if (entry.getKey().stage().equals(stage)) {
                    oldest = Math.max(oldest, entry.getValue().oldestDueAgeSeconds());
                }
            }
            return oldest;
        }

        /** 모든 stage 의 PENDING 중 가장 오래된 due 나이. */
        public double oldestDueAgeAnyStage() {
            double oldest = 0;
            for (Map.Entry<StageKey, StageMetrics> entry : stages.entrySet()) {
                if ("PENDING".equals(entry.getKey().status())) {
                    oldest = Math.max(oldest, entry.getValue().oldestDueAgeSeconds());
                }
            }
            return oldest;
        }

        public record StageKey(String stage, String status) {}

        public record StageMetrics(long total, double oldestDueAgeSeconds) {}
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
