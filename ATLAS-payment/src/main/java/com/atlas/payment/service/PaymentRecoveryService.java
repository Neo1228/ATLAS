package com.atlas.payment.service;

import com.atlas.payment.dto.response.PaymentRecoveryJob;
import com.atlas.payment.dto.response.PaymentRecoveryRequeue;
import com.atlas.payment.dto.response.PaymentRecoveryReviewItem;
import com.atlas.payment.persistence.state.PaymentRecoveryJobStatus;
import com.atlas.payment.persistence.state.PaymentRecoveryWorkStage;
import com.atlas.payment.dto.response.PaymentRecoveryMetrics;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PaymentRecoveryService {
    void enqueueAttempt(UUID attemptId, Duration initialDelay);
    void activateAttemptFromBuyer(UUID attemptId, Duration initialDelay);
    void enqueueRefund(UUID refundOperationId, Duration initialDelay);
    void markAttemptForApplication(UUID attemptId);
    void markRefundForApplication(UUID refundOperationId);
    void markAttemptApplied(UUID attemptId);
    void markRefundApplied(UUID refundOperationId);
    void expireExhausted(int maxAttempts, Duration maxAge);
    /** 승인 deadline이 지난 {@code WAITING_FOR_BUYER} job을 다시 worker 대상으로 되돌린다. */
    int resumeExpiredApprovalWaits(int limit);
    List<PaymentRecoveryJob> claimDue(
            int limit, UUID leaseToken, Duration leaseDuration, int maxAttempts, Duration maxAge);
    List<PaymentRecoveryJob> claimNextDue(
            int limit, UUID leaseToken, Duration leaseDuration, int maxAttempts, Duration maxAge);
    boolean complete(PaymentRecoveryJob job);
    boolean waitForBuyer(PaymentRecoveryJob job);
    boolean reschedule(
            PaymentRecoveryJob job, Duration delay, int maxAttempts,
            Duration maxAge, String errorCode);
    boolean markNeedsReview(PaymentRecoveryJob job, String errorCode);
    List<PaymentRecoveryReviewItem> listNeedsReview(int limit, int offset);

    /**
     * 운영자가 {@code NEEDS_REVIEW} job을 수동으로 다시 시도 대상으로 올린다. 같은 멱등 키는 이전 결과를 재생한다.
     *
     * @return 재큐 결과. job이 대상이 아니거나 수동 재큐 한도({@code maxManualRequeues})에 도달했으면 비어 있다.
     */
    Optional<PaymentRecoveryRequeue> requeueNeedsReview(
            UUID recoveryJobId, String idempotencyKey, String actor, String reason, int maxManualRequeues);
    /**
     * 메트릭용 스냅샷. 내부에서 짧은 TTL 로 캐시하므로 스크레이프마다 호출해도 DB 부하가
     * 스크레이프 빈도에 비례하지 않는다.
     */
    PaymentRecoveryMetrics metricsSnapshot();

    long count(PaymentRecoveryJobStatus status);
    long count(PaymentRecoveryJobStatus status, PaymentRecoveryWorkStage stage);
    double oldestDueAgeSeconds(PaymentRecoveryWorkStage stage);
    double oldestDueAgeSeconds();
}