package com.atlas.payment.service.impl;

import com.atlas.payment.dto.response.PaymentRecoveryJob;
import com.atlas.payment.dto.response.PaymentRecoveryReviewItem;
import com.atlas.payment.persistence.projection.ClaimedPaymentRecoveryJob;
import com.atlas.payment.persistence.projection.PaymentRecoveryReviewRow;
import com.atlas.payment.persistence.state.PaymentRecoveryJobStatus;
import com.atlas.payment.persistence.state.PaymentRecoveryWorkStage;
import com.atlas.payment.repository.PaymentRecoveryRepository;
import com.atlas.payment.service.PaymentRecoveryService;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class PaymentRecoveryServiceImpl implements PaymentRecoveryService {
    private final PaymentRecoveryRepository recovery;

    public PaymentRecoveryServiceImpl(PaymentRecoveryRepository recovery) {
        this.recovery = Objects.requireNonNull(recovery);
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
    public List<PaymentRecoveryJob> claimDue(
            int limit, UUID leaseToken, Duration leaseDuration, int maxAttempts, Duration maxAge) {
        return recovery.claimDue(limit, leaseToken, leaseDuration, maxAttempts, maxAge).stream()
                .map(PaymentRecoveryServiceImpl::toDto)
                .toList();
    }

    @Override
    public List<PaymentRecoveryJob> claimNextDue(
            int limit, UUID leaseToken, Duration leaseDuration, int maxAttempts, Duration maxAge) {
        return recovery.claimNextDue(limit, leaseToken, leaseDuration, maxAttempts, maxAge).stream()
                .map(PaymentRecoveryServiceImpl::toDto)
                .toList();
    }

    @Override
    public boolean complete(PaymentRecoveryJob job) {
        return recovery.complete(toProjection(job));
    }

    @Override
    public boolean waitForBuyer(PaymentRecoveryJob job) {
        return recovery.waitForBuyer(toProjection(job));
    }

    @Override
    public boolean reschedule(
            PaymentRecoveryJob job, Duration delay, int maxAttempts,
            Duration maxAge, String errorCode) {
        return recovery.reschedule(toProjection(job), delay, maxAttempts, maxAge, errorCode);
    }

    @Override
    public boolean markNeedsReview(PaymentRecoveryJob job, String errorCode) {
        return recovery.markNeedsReview(toProjection(job), errorCode);
    }

    @Override
    public List<PaymentRecoveryReviewItem> listNeedsReview(int limit, int offset) {
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

    private static PaymentRecoveryJob toDto(ClaimedPaymentRecoveryJob job) {
        return new PaymentRecoveryJob(job.recoveryJobId(), job.operationType(), job.workStage(),
                job.operationId(), job.attemptCount(), job.firstEnqueuedAt(), job.leaseToken());
    }

    private static ClaimedPaymentRecoveryJob toProjection(PaymentRecoveryJob job) {
        return new ClaimedPaymentRecoveryJob(
                job.recoveryJobId(), job.operationType(), job.workStage(), job.operationId(),
                job.attemptCount(), job.firstEnqueuedAt(), job.leaseToken());
    }

    private static PaymentRecoveryReviewItem toDto(PaymentRecoveryReviewRow item) {
        return new PaymentRecoveryReviewItem(item.recoveryJobId(), item.operationType(), item.workStage(),
                item.operationId(), item.paymentAttemptId(), item.orderId(), item.checkoutId(), item.attemptCount(),
                item.manualRequeueCount(), item.firstEnqueuedAt(), item.lastAttemptAt(), item.lastErrorCode());
    }
}