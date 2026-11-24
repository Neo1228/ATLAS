package com.atlas.payment.service.impl;

import com.atlas.payment.dto.response.PaymentRecoveryJobResponse;
import com.atlas.payment.dto.response.PaymentRecoveryRequeueResponse;
import com.atlas.payment.dto.response.PaymentRecoveryReviewItemResponse;
import com.atlas.payment.dto.response.PaymentRecoveryMetricsResponse;
import com.atlas.payment.persistence.projection.ClaimedPaymentRecoveryJob;
import com.atlas.payment.persistence.projection.PaymentRecoveryRequeueRow;
import com.atlas.payment.persistence.projection.PaymentRecoveryReviewRow;
import com.atlas.payment.persistence.projection.RecoveryMetricsSnapshot;
import com.atlas.payment.model.PaymentRecoveryJobStatus;
import com.atlas.payment.model.PaymentRecoveryWorkStage;
import com.atlas.payment.repository.PaymentRecoveryRepository;
import com.atlas.payment.service.PaymentRecoveryService;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PaymentRecoveryServiceImpl implements PaymentRecoveryService {
    /**
     * 메트릭 스냅샷 캐시 TTL. Prometheus 스크레이프 주기(보통 15초)보다 짧게 두어 신선도를
     * 유지하면서, 여러 스크레이퍼나 비인증 호출이 같은 스냅샷을 공유하게 한다.
     *
     * <p>왜 캐시가 필요한가: Micrometer Gauge 는 스크레이프마다 값을 읽는다. 이전 구현은
     * Gauge 16개가 각각 COUNT(*) 를 실행해, 스크레이프 빈도가 곧 DB 부하가 됐다.
     * {@code /actuator/prometheus} 는 인증 없이 열려 있어 그 빈도를 누구나 조절할 수 있었다.
     */
    static final Duration METRICS_CACHE_TTL = Duration.ofSeconds(5);

    private final PaymentRecoveryRepository recovery;

    private volatile PaymentRecoveryMetricsResponse metricsSnapshot;
    private volatile long metricsFetchedAtNanos = Long.MIN_VALUE;

    public PaymentRecoveryServiceImpl(PaymentRecoveryRepository recovery) {
        this.recovery = Objects.requireNonNull(recovery);
    }

    /**
     * 모든 메트릭 Gauge 가 공유하는 스냅샷. TTL 이 지나면 단 한 번만 DB 를 읽는다.
     *
     * <p>동시 스크레이프가 겹쳐도 여러 쿼리가 동시에 나가지 않도록 메서드 수준에서 직렬화한다.
     * 이 경로는 초당 소수 회만 호출되므로 경합 비용은 무시할 수 있다.
     */
    @Override
    public synchronized PaymentRecoveryMetricsResponse metricsSnapshot() {
        long now = System.nanoTime();
        if (metricsSnapshot != null
                && now - metricsFetchedAtNanos < METRICS_CACHE_TTL.toNanos()) {
            return metricsSnapshot;
        }
        PaymentRecoveryMetricsResponse fresh = toMetrics(recovery.metricsSnapshot());
        metricsSnapshot = fresh;
        metricsFetchedAtNanos = now;
        return fresh;
    }

    @Override
    public void enqueueAttempt(UUID attemptId, Duration initialDelay) {
        recovery.enqueueAttempt(attemptId, initialDelay);
    }

    @Override
    public void activateAttemptFromBuyer(UUID attemptId, Duration initialDelay) {
        recovery.activateAttemptFromBuyer(attemptId, initialDelay);
    }

    @Override
    public void enqueueRefund(UUID refundOperationId, Duration initialDelay) {
        recovery.enqueueRefund(refundOperationId, initialDelay);
    }


    @Override
    public void markAttemptForApplication(UUID attemptId) {
        recovery.markAttemptForApplication(attemptId);
    }

    @Override
    public void markRefundForApplication(UUID refundOperationId) {
        recovery.markRefundForApplication(refundOperationId);
    }

    @Override
    public void markAttemptApplied(UUID attemptId) {
        recovery.markAttemptApplied(attemptId);
    }

    @Override
    public void markRefundApplied(UUID refundOperationId) {
        recovery.markRefundApplied(refundOperationId);
    }
    @Override
    public void expireExhausted(int maxAttempts, Duration maxAge) {
        recovery.expireExhausted(maxAttempts, maxAge);
    }

    @Override
    public int resumeExpiredApprovalWaits(int limit) {
        return recovery.resumeExpiredApprovalWaits(limit);
    }

    @Override
    public List<PaymentRecoveryJobResponse> claimDue(
            int limit, UUID leaseToken, Duration leaseDuration, int maxAttempts, Duration maxAge) {
        return recovery.claimDue(limit, leaseToken, leaseDuration, maxAttempts, maxAge).stream()
                .map(PaymentRecoveryServiceImpl::toDto)
                .toList();
    }

    @Override
    public List<PaymentRecoveryJobResponse> claimNextDue(
            int limit, UUID leaseToken, Duration leaseDuration, int maxAttempts, Duration maxAge) {
        return recovery.claimNextDue(limit, leaseToken, leaseDuration, maxAttempts, maxAge).stream()
                .map(PaymentRecoveryServiceImpl::toDto)
                .toList();
    }

    @Override
    public boolean complete(PaymentRecoveryJobResponse job) {
        return recovery.complete(toProjection(job));
    }

    @Override
    public boolean waitForBuyer(PaymentRecoveryJobResponse job) {
        return recovery.waitForBuyer(toProjection(job));
    }

    @Override
    public boolean reschedule(
            PaymentRecoveryJobResponse job, Duration delay, int maxAttempts,
            Duration maxAge, String errorCode) {
        return recovery.reschedule(toProjection(job), delay, maxAttempts, maxAge, errorCode);
    }

    @Override
    public boolean markNeedsReview(PaymentRecoveryJobResponse job, String errorCode) {
        return recovery.markNeedsReview(toProjection(job), errorCode);
    }

    @Override
    @Transactional
    public Optional<PaymentRecoveryRequeueResponse> requeueNeedsReview(
            UUID recoveryJobId, String idempotencyKey, String actor, String reason, int maxManualRequeues) {
        Objects.requireNonNull(recoveryJobId, "recoveryJobId");
        Objects.requireNonNull(idempotencyKey, "idempotencyKey");
        Objects.requireNonNull(actor, "actor");
        Objects.requireNonNull(reason, "reason");
        if (maxManualRequeues < 1) {
            throw new IllegalArgumentException("maxManualRequeues must be positive");
        }
        return recovery.requeueNeedsReview(recoveryJobId, idempotencyKey, actor, reason, maxManualRequeues)
                .map(PaymentRecoveryServiceImpl::toRequeue);
    }

    @Override
    public List<PaymentRecoveryReviewItemResponse> listNeedsReview(int limit, int offset) {
        return recovery.listNeedsReview(limit, offset).stream()
                .map(PaymentRecoveryServiceImpl::toDto)
                .toList();
    }

    @Override
    public long count(PaymentRecoveryJobStatus status) {
        return recovery.count(status);
    }

    @Override
    public long count(PaymentRecoveryJobStatus status, PaymentRecoveryWorkStage stage) {
        return recovery.count(status, stage);
    }

    @Override
    public double oldestDueAgeSeconds() {
        return recovery.oldestDueAgeSeconds();
    }

    @Override
    public double oldestDueAgeSeconds(PaymentRecoveryWorkStage stage) {
        return recovery.oldestDueAgeSeconds(stage);
    }

    private static PaymentRecoveryJobResponse toDto(ClaimedPaymentRecoveryJob job) {
        return new PaymentRecoveryJobResponse(job.recoveryJobId(), job.operationType(), job.workStage(),
                job.operationId(), job.attemptCount(), job.firstEnqueuedAt(), job.leaseToken());
    }

    private static ClaimedPaymentRecoveryJob toProjection(PaymentRecoveryJobResponse job) {
        return new ClaimedPaymentRecoveryJob(
                job.recoveryJobId(), job.operationType(), job.workStage(), job.operationId(),
                job.attemptCount(), job.firstEnqueuedAt(), job.leaseToken());
    }

    private static PaymentRecoveryReviewItemResponse toDto(PaymentRecoveryReviewRow item) {
        return new PaymentRecoveryReviewItemResponse(item.recoveryJobId(), item.operationType(), item.workStage(),
                item.operationId(), item.paymentAttemptId(), item.orderId(), item.checkoutId(), item.attemptCount(),
                item.manualRequeueCount(), item.firstEnqueuedAt(), item.lastAttemptAt(), item.lastErrorCode());
    }

    private static PaymentRecoveryMetricsResponse toMetrics(
            RecoveryMetricsSnapshot snapshot) {
        Map<PaymentRecoveryMetricsResponse.StageKey, PaymentRecoveryMetricsResponse.StageMetrics> stages =
                new HashMap<>();
        snapshot.stages().forEach((key, value) -> stages.put(
                new PaymentRecoveryMetricsResponse.StageKey(key.stage(), key.status()),
                new PaymentRecoveryMetricsResponse.StageMetrics(value.total(), value.oldestDueAgeSeconds())));
        return new PaymentRecoveryMetricsResponse(Map.copyOf(snapshot.statusCounts()), Map.copyOf(stages));
    }

    private static PaymentRecoveryRequeueResponse toRequeue(PaymentRecoveryRequeueRow row) {
        return new PaymentRecoveryRequeueResponse(
                row.recoveryJobId(), row.status(), row.manualRequeueCount(), row.acceptedAt(), row.duplicate());
    }
}
