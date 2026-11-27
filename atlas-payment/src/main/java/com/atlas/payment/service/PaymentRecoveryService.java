package com.atlas.payment.service;

import com.atlas.payment.dto.response.PaymentRecoveryResponse;
import com.atlas.payment.model.PaymentRecoveryJobStatus;
import com.atlas.payment.model.PaymentRecoveryWorkStage;
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
    /**
     * 한도를 넘긴 job을 {@code NEEDS_REVIEW}로 넘긴다. {@code maxAbandonedClaims}는 결과를 쓰지 못하고 lease가
     * 만료된 claim(프로세스 종료 등)의 한도이고, {@code maxAttempts}는 전체 claim 수의 안전 상한이다(ADR-0106).
     */
    void expireExhausted(int maxAttempts, int maxAbandonedClaims, Duration maxAge);
    /** 승인 deadline이 지난 {@code WAITING_FOR_BUYER} job을 다시 worker 대상으로 되돌린다. */
    int resumeExpiredApprovalWaits(int limit);
    List<PaymentRecoveryResponse.Job> claimDue(
            int limit, UUID leaseToken, Duration leaseDuration, int maxAttempts, int maxAbandonedClaims,
            Duration maxAge);
    List<PaymentRecoveryResponse.Job> claimNextDue(
            int limit, UUID leaseToken, Duration leaseDuration, int maxAttempts, Duration maxAge);
    boolean complete(PaymentRecoveryResponse.Job job);
    boolean waitForBuyer(PaymentRecoveryResponse.Job job);
    boolean reschedule(
            PaymentRecoveryResponse.Job job, Duration delay, int maxAttempts,
            Duration maxAge, String errorCode);
    boolean markNeedsReview(PaymentRecoveryResponse.Job job, String errorCode);
    List<PaymentRecoveryResponse.ReviewItem> listNeedsReview(int limit, int offset);

    /**
     * 운영자가 {@code NEEDS_REVIEW} job을 수동으로 다시 시도 대상으로 올린다. 같은 멱등 키는 이전 결과를 재생한다.
     *
     * @return 재큐 결과. job이 대상이 아니거나 수동 재큐 한도({@code maxManualRequeues})에 도달했으면 비어 있다.
     */
    Optional<PaymentRecoveryResponse.Requeue> requeueNeedsReview(
            UUID recoveryJobId, String idempotencyKey, String actor, String reason, int maxManualRequeues);
    /**
     * 메트릭용 스냅샷. 내부에서 짧은 TTL 로 캐시하므로 스크레이프마다 호출해도 DB 부하가
     * 스크레이프 빈도에 비례하지 않는다.
     */
    long statusCount(PaymentRecoveryJobStatus status);

    long stageCount(PaymentRecoveryWorkStage stage, PaymentRecoveryJobStatus status);

    double stageOldestDueAgeSeconds(PaymentRecoveryWorkStage stage);

    double oldestDueAgeAnyStage();

    long count(PaymentRecoveryJobStatus status);
    long count(PaymentRecoveryJobStatus status, PaymentRecoveryWorkStage stage);
    double oldestDueAgeSeconds(PaymentRecoveryWorkStage stage);
    double oldestDueAgeSeconds();
}