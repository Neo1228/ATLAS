package com.atlas.payment.dto.response;

import java.util.Map;

/**
 * 복구 큐 메트릭의 service 계약 타입. Prometheus Gauge 가 읽는다.
 *
 * <p>repository 의 projection 을 service 인터페이스에 그대로 노출하면 계층 규칙을 위반하므로,
 * service 가 이 응답 타입으로 변환해 돌려준다.
 */
public record PaymentRecoveryMetricsResponse(
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
