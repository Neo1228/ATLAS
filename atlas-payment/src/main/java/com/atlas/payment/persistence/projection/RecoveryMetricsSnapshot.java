package com.atlas.payment.persistence.projection;

import com.atlas.payment.model.PaymentRecoveryJobStatus;
import com.atlas.payment.model.PaymentRecoveryWorkStage;
import java.util.Map;

/**
 * 복구 큐 메트릭 스냅샷. Prometheus Gauge 가 읽는 값이다.
 *
 * <p>이 타입이 {@code persistence.projection} 에 있는 이유: repository 가 반환하는 값이므로
 * repository 계층의 projection 이다. repository 가 public DTO 를 반환하면 계층 규칙을 위반한다.
 * service 인터페이스는 이 projection 대신 자체 응답 타입을 쓴다.
 *
 * <p>키는 DB 문자열이 아니라 enum 이름이며, 호출자가 이미 enum 상수를 알고 있다.
 */
public record RecoveryMetricsSnapshot(
        Map<PaymentRecoveryJobStatus, Long> statusCounts,
        Map<StageKey, StageMetrics> stages) {

    public long statusCount(PaymentRecoveryJobStatus status) {
        Long value = statusCounts.get(status);
        return value == null ? 0L : value;
    }

    public long stageCount(PaymentRecoveryWorkStage stage, PaymentRecoveryJobStatus status) {
        StageMetrics metrics = stages.get(new StageKey(stage, status));
        return metrics == null ? 0L : metrics.total();
    }

    public double stageOldestDueAgeSeconds(PaymentRecoveryWorkStage stage) {
        double oldest = 0;
        for (Map.Entry<StageKey, StageMetrics> entry : stages.entrySet()) {
            if (entry.getKey().stage() == stage) {
                oldest = Math.max(oldest, entry.getValue().oldestDueAgeSeconds());
            }
        }
        return oldest;
    }

    /**
     * 복구가 얼마나 밀렸는지 하나로 보여주는 값: 모든 stage 의 PENDING 중 가장 오래된 due 나이.
     */
    public double oldestDueAgeAnyStage() {
        double oldest = 0;
        for (Map.Entry<StageKey, StageMetrics> entry : stages.entrySet()) {
            if (entry.getKey().status() == PaymentRecoveryJobStatus.PENDING) {
                oldest = Math.max(oldest, entry.getValue().oldestDueAgeSeconds());
            }
        }
        return oldest;
    }

    public record StageKey(PaymentRecoveryWorkStage stage, PaymentRecoveryJobStatus status) {}

    public record StageMetrics(long total, double oldestDueAgeSeconds) {}
}
