package com.atlas.payment.repository;

import com.atlas.payment.persistence.projection.ClaimedPaymentRecoveryJob;
import com.atlas.payment.persistence.projection.PaymentRecoveryReviewRow;
import com.atlas.payment.persistence.state.PaymentRecoveryJobStatus;
import com.atlas.payment.persistence.state.PaymentRecoveryOperationType;
import com.atlas.payment.persistence.state.PaymentRecoveryWorkStage;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class PaymentRecoveryRepository {
    private static final int MAX_BATCH_SIZE = 100;
    private final JdbcTemplate jdbc;

    public PaymentRecoveryRepository(JdbcTemplate jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc);
    }

    public void enqueueAttempt(UUID attemptId, Duration initialDelay) {
        Objects.requireNonNull(attemptId);
        long delayMillis = nonNegativeMillis(initialDelay, "initialDelay");
        jdbc.update("""
                INSERT INTO payment_recovery_job (
                    recovery_job_id, payment_attempt_id, operation_type, status,
                    available_at, first_enqueued_at, retry_window_started_at, updated_at
                )
                VALUES (?, ?, 'PAYMENT_ATTEMPT', 'PENDING',
                    CURRENT_TIMESTAMP + (? * INTERVAL '1 millisecond'),
                    CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                ON CONFLICT (payment_attempt_id) DO NOTHING
                """, UUID.randomUUID(), attemptId, delayMillis);
    }
    public void resumeAttempt(UUID attemptId, Duration initialDelay) {
        Objects.requireNonNull(attemptId);
        long delayMillis = nonNegativeMillis(initialDelay, "initialDelay");
        int updated = jdbc.update("""
                UPDATE payment_recovery_job
                SET work_stage = 'PROVIDER',
                    status = 'PENDING',
                    attempt_count = 0,
                    retry_window_started_at = CURRENT_TIMESTAMP,
                    available_at = CASE
                        WHEN status = 'PENDING' AND lease_until > CURRENT_TIMESTAMP THEN available_at
                        ELSE CURRENT_TIMESTAMP + (? * INTERVAL '1 millisecond') END,
                    lease_token = CASE
                        WHEN status = 'PENDING' AND lease_until > CURRENT_TIMESTAMP THEN lease_token
                        ELSE NULL END,
                    lease_until = CASE
                        WHEN status = 'PENDING' AND lease_until > CURRENT_TIMESTAMP THEN lease_until
                        ELSE NULL END,
                    updated_at = CURRENT_TIMESTAMP
                WHERE payment_attempt_id = ?
                  AND (status = 'WAITING_FOR_BUYER'
                    OR (work_stage = 'APPLY' AND status IN ('PENDING', 'NEEDS_REVIEW')))
                """, delayMillis, attemptId);
        if (updated == 0) enqueueAttempt(attemptId, initialDelay);
    }

    public void activateAttemptFromBuyer(UUID attemptId, Duration initialDelay) {
        Objects.requireNonNull(attemptId);
        long delayMillis = nonNegativeMillis(initialDelay, "initialDelay");
        int updated = jdbc.update("""
                UPDATE payment_recovery_job
                SET work_stage = 'PROVIDER',
                    status = 'PENDING',
                    attempt_count = 0,
                    retry_window_started_at = CURRENT_TIMESTAMP,
                    available_at = CURRENT_TIMESTAMP + (? * INTERVAL '1 millisecond'),
                    lease_token = NULL,
                    lease_until = NULL,
                    updated_at = CURRENT_TIMESTAMP
                WHERE payment_attempt_id = ?
                  AND status IN ('PENDING', 'WAITING_FOR_BUYER')
                """, delayMillis, attemptId);
        if (updated == 0) enqueueAttempt(attemptId, initialDelay);
    }

    public void enqueueRefund(UUID refundOperationId, Duration initialDelay) {
        Objects.requireNonNull(refundOperationId);
        long delayMillis = nonNegativeMillis(initialDelay, "initialDelay");
        jdbc.update("""
                INSERT INTO payment_recovery_job (
                    recovery_job_id, payment_refund_operation_id, operation_type, status,
                    available_at, first_enqueued_at, retry_window_started_at, updated_at
                )
                VALUES (?, ?, 'PAYMENT_REFUND', 'PENDING',
                    CURRENT_TIMESTAMP + (? * INTERVAL '1 millisecond'),
                    CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                ON CONFLICT (payment_refund_operation_id) DO NOTHING
                """, UUID.randomUUID(), refundOperationId, delayMillis);
    }

    public int markAttemptForApplication(UUID attemptId) {
        return markForApplication("payment_attempt_id", attemptId);
    }

    public int markRefundForApplication(UUID refundOperationId) {
        return markForApplication("payment_refund_operation_id", refundOperationId);
    }

    private int markForApplication(String operationColumn, UUID operationId) {
        Objects.requireNonNull(operationId);
        return jdbc.update("""
                UPDATE payment_recovery_job
                SET work_stage = 'APPLY',
                    status = 'PENDING',
                    attempt_count = 0,
                    retry_window_started_at = CURRENT_TIMESTAMP,
                    available_at = CURRENT_TIMESTAMP,
                    lease_token = CASE
                        WHEN status = 'PENDING' AND lease_until > CURRENT_TIMESTAMP THEN lease_token
                        ELSE NULL END,
                    lease_until = CASE
                        WHEN status = 'PENDING' AND lease_until > CURRENT_TIMESTAMP THEN lease_until
                        ELSE NULL END,
                    last_error_code = NULL,
                    updated_at = CURRENT_TIMESTAMP
                WHERE %s = ?
                  AND status <> 'COMPLETED'
                """.formatted(operationColumn), operationId);
    }

    public int markAttemptApplied(UUID attemptId) {
        return markApplied("payment_attempt_id", attemptId);
    }

    public int markRefundApplied(UUID refundOperationId) {
        return markApplied("payment_refund_operation_id", refundOperationId);
    }

    private int markApplied(String operationColumn, UUID operationId) {
        Objects.requireNonNull(operationId);
        return jdbc.update("""
                UPDATE payment_recovery_job
                SET status = 'COMPLETED',
                    lease_token = NULL,
                    lease_until = NULL,
                    last_error_code = NULL,
                    updated_at = CURRENT_TIMESTAMP
                WHERE %s = ?
                  AND work_stage = 'APPLY'
                """.formatted(operationColumn), operationId);
    }

    public List<ClaimedPaymentRecoveryJob> claimDue(
            int limit, UUID leaseToken, Duration leaseDuration, int maxAttempts, Duration maxAge) {
        return claimDue(limit, leaseToken, leaseDuration, maxAttempts, maxAge, true);
    }

    public List<ClaimedPaymentRecoveryJob> claimNextDue(
            int limit, UUID leaseToken, Duration leaseDuration, int maxAttempts, Duration maxAge) {
        return claimDue(limit, leaseToken, leaseDuration, maxAttempts, maxAge, false);
    }

    private List<ClaimedPaymentRecoveryJob> claimDue(
            int limit, UUID leaseToken, Duration leaseDuration,
            int maxAttempts, Duration maxAge, boolean sweepExhausted) {
        if (limit < 1 || limit > MAX_BATCH_SIZE) {
            throw new IllegalArgumentException("limit must be between 1 and 100");
        }
        if (maxAttempts < 1) {
            throw new IllegalArgumentException("maxAttempts must be positive");
        }
        Objects.requireNonNull(leaseToken);
        long leaseMillis = positiveMillis(leaseDuration, "leaseDuration");
        long maxAgeMillis = positiveMillis(maxAge, "maxAge");
        if (sweepExhausted) expireExhausted(maxAttempts, maxAgeMillis);
        return jdbc.query("""
                WITH candidates AS (
                    SELECT recovery_job_id
                    FROM payment_recovery_job
                    WHERE status = 'PENDING'
                      AND available_at <= CURRENT_TIMESTAMP
                      AND (lease_until IS NULL OR lease_until <= CURRENT_TIMESTAMP)
                    ORDER BY available_at, first_enqueued_at, recovery_job_id
                    LIMIT ?
                    FOR UPDATE SKIP LOCKED
                )
                UPDATE payment_recovery_job AS job
                SET lease_token = ?,
                    lease_until = CURRENT_TIMESTAMP + (? * INTERVAL '1 millisecond'),
                    attempt_count = job.attempt_count + 1,
                    last_attempt_at = CURRENT_TIMESTAMP,
                    updated_at = CURRENT_TIMESTAMP
                FROM candidates
                WHERE job.recovery_job_id = candidates.recovery_job_id
                RETURNING job.recovery_job_id, job.operation_type, job.work_stage,
                    job.payment_attempt_id, job.payment_refund_operation_id,
                    job.attempt_count, job.first_enqueued_at, job.lease_token
                """, (result, rowNumber) -> mapClaimed(result), limit, leaseToken, leaseMillis);
    }

    public boolean complete(ClaimedPaymentRecoveryJob job) {
        Objects.requireNonNull(job);
        if (updateClaimed(job, "COMPLETED", null)) return true;
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS (
                    SELECT 1 FROM payment_recovery_job
                    WHERE recovery_job_id = ?
                      AND status = 'COMPLETED'
                      AND work_stage = 'APPLY'
                )
                """, Boolean.class, job.recoveryJobId()));
    }

    public boolean waitForBuyer(ClaimedPaymentRecoveryJob job) {
        Objects.requireNonNull(job);
        return jdbc.update("""
                UPDATE payment_recovery_job AS work
                SET status = 'WAITING_FOR_BUYER',
                    lease_token = NULL,
                    lease_until = NULL,
                    updated_at = CURRENT_TIMESTAMP
                FROM payment_attempt AS attempt
                WHERE work.recovery_job_id = ?
                  AND work.lease_token = ?
                  AND work.status = 'PENDING'
                  AND work.work_stage = 'PROVIDER'
                  AND work.payment_attempt_id = attempt.attempt_id
                  AND attempt.state = 'AUTHORIZATION_PENDING'
                  AND attempt.payment_phase = 'AUTHORIZATION'
                  AND attempt.provider_order_id IS NOT NULL
                  AND attempt.approval_url IS NOT NULL
                  AND length(trim(attempt.approval_url)) > 0
                  -- deadline 없는 대기 행을 만들지 않는다. 있으면 재개 sweep이 확정할 수 있다.
                  AND attempt.approval_expires_at IS NOT NULL
                """, job.recoveryJobId(), job.leaseToken()) > 0;
    }

    public boolean reschedule(
            ClaimedPaymentRecoveryJob job, Duration delay, int maxAttempts,
            Duration maxAge, String errorCode) {
        long delayMillis = nonNegativeMillis(delay, "delay");
        long maxAgeMillis = positiveMillis(maxAge, "maxAge");
        if (maxAttempts < 1) {
            throw new IllegalArgumentException("maxAttempts must be positive");
        }
        String safeError = safeErrorCode(errorCode);
        return jdbc.update("""
                UPDATE payment_recovery_job
                SET status = CASE
                        WHEN attempt_count >= ?
                          OR retry_window_started_at <= CURRENT_TIMESTAMP
                                - (? * INTERVAL '1 millisecond')
                        THEN 'NEEDS_REVIEW' ELSE 'PENDING' END,
                    available_at = CURRENT_TIMESTAMP + (? * INTERVAL '1 millisecond'),
                    lease_token = NULL,
                    lease_until = NULL,
                    last_error_code = ?,
                    updated_at = CURRENT_TIMESTAMP
                WHERE recovery_job_id = ? AND lease_token = ? AND status = 'PENDING'
                """, maxAttempts, maxAgeMillis, delayMillis, safeError,
                job.recoveryJobId(), job.leaseToken()) > 0;
    }

    public boolean markNeedsReview(ClaimedPaymentRecoveryJob job, String errorCode) {
        return updateClaimed(job, "NEEDS_REVIEW", safeErrorCode(errorCode));
    }

    public List<PaymentRecoveryReviewRow> listNeedsReview(int limit, int offset) {
        if (limit < 1 || limit > MAX_BATCH_SIZE || offset < 0 || offset > 10_000) {
            throw new IllegalArgumentException("review page is outside the allowed range");
        }
        return jdbc.query("""
                SELECT job.recovery_job_id, job.operation_type, job.work_stage,
                    job.payment_attempt_id, job.payment_refund_operation_id,
                    CASE WHEN job.operation_type = 'PAYMENT_ATTEMPT'
                        THEN attempt.attempt_id ELSE refund_attempt.attempt_id END AS related_payment_attempt_id,
                    CASE WHEN job.operation_type = 'PAYMENT_ATTEMPT'
                        THEN attempt.order_id ELSE refund_attempt.order_id END AS related_order_id,
                    CASE WHEN job.operation_type = 'PAYMENT_ATTEMPT'
                        THEN attempt.checkout_id ELSE refund_attempt.checkout_id END AS related_checkout_id,
                    job.attempt_count, job.manual_requeue_count,
                    job.first_enqueued_at, job.last_attempt_at, job.last_error_code
                FROM payment_recovery_job AS job
                LEFT JOIN payment_attempt AS attempt ON attempt.attempt_id = job.payment_attempt_id
                LEFT JOIN payment_refund_operation AS refund_operation
                    ON refund_operation.refund_operation_id = job.payment_refund_operation_id
                LEFT JOIN payment_attempt AS refund_attempt ON refund_attempt.attempt_id = refund_operation.attempt_id
                WHERE job.status = 'NEEDS_REVIEW'
                ORDER BY job.first_enqueued_at DESC, job.recovery_job_id DESC
                LIMIT ? OFFSET ?
                """, (result, rowNumber) -> mapReviewItem(result), limit, offset);
    }

    public long count(PaymentRecoveryJobStatus status) {
        Objects.requireNonNull(status);
        Long count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM payment_recovery_job WHERE status = ?",
                Long.class, status.name());
        return count == null ? 0 : count;
    }

    public long count(PaymentRecoveryJobStatus status, PaymentRecoveryWorkStage stage) {
        Objects.requireNonNull(status);
        Objects.requireNonNull(stage);
        Long count = jdbc.queryForObject("""
                SELECT COUNT(*) FROM payment_recovery_job
                WHERE status = ? AND work_stage = ?
                """, Long.class, status.name(), stage.name());
        return count == null ? 0 : count;
    }

    public double oldestDueAgeSeconds() {
        Double seconds = jdbc.queryForObject("""
                SELECT COALESCE(
                    EXTRACT(EPOCH FROM CURRENT_TIMESTAMP - MIN(available_at)),
                    0
                )::double precision
                FROM payment_recovery_job
                WHERE status = 'PENDING'
                  AND available_at <= CURRENT_TIMESTAMP
                """, Double.class);
        return seconds == null ? 0 : Math.max(0, seconds);
    }

    public double oldestDueAgeSeconds(PaymentRecoveryWorkStage stage) {
        Objects.requireNonNull(stage);
        Double seconds = jdbc.queryForObject("""
                SELECT COALESCE(
                    EXTRACT(EPOCH FROM CURRENT_TIMESTAMP - MIN(available_at)),
                    0
                )::double precision
                FROM payment_recovery_job
                WHERE status = 'PENDING'
                  AND work_stage = ?
                  AND available_at <= CURRENT_TIMESTAMP
                """, Double.class, stage.name());
        return seconds == null ? 0 : Math.max(0, seconds);
    }

    public void expireExhausted(int maxAttempts, Duration maxAge) {
        if (maxAttempts < 1) {
            throw new IllegalArgumentException("maxAttempts must be positive");
        }
        expireExhausted(maxAttempts, positiveMillis(maxAge, "maxAge"));
    }

    /**
     * 승인 화면에서 이탈한 결제를 다시 worker 대상으로 되돌린다.
     *
     * <p>{@code WAITING_FOR_BUYER}는 외부 callback 없이는 빠져나오지 못하므로, deadline이 지난
     * job을 {@code PENDING}으로 재개해 provider 상태 조회와 owner 적용을 다시 태운다. 재개
     * 사유를 {@code last_error_code}에 남겨 자동 재개와 buyer callback을 구분한다.
     */
    public int resumeExpiredApprovalWaits(int limit) {
        if (limit < 1 || limit > MAX_BATCH_SIZE) {
            throw new IllegalArgumentException("limit must be between 1 and 100");
        }
        return jdbc.update("""
                WITH expired AS (
                    SELECT work.recovery_job_id
                    FROM payment_recovery_job AS work
                    JOIN payment_attempt AS attempt ON attempt.attempt_id = work.payment_attempt_id
                    WHERE work.status = 'WAITING_FOR_BUYER'
                      AND work.work_stage = 'PROVIDER'
                      -- deadline이 NULL이면 "대기 시작 시각을 기록하지 못한 행"이다. 정상 경로는
                      -- 진입 시 항상 deadline을 남기므로, NULL은 즉시 확정 대상으로 본다. 그렇지
                      -- 않으면 이 행들은 재개 대상에서도 만료 대상에서도 빠져 영원히 남는다.
                      AND (attempt.approval_expires_at IS NULL
                           OR attempt.approval_expires_at <= CURRENT_TIMESTAMP)
                    ORDER BY attempt.approval_expires_at NULLS FIRST, work.recovery_job_id
                    LIMIT ?
                    FOR UPDATE OF work SKIP LOCKED
                )
                UPDATE payment_recovery_job AS work
                SET status = 'PENDING',
                    attempt_count = 0,
                    retry_window_started_at = CURRENT_TIMESTAMP,
                    available_at = CURRENT_TIMESTAMP,
                    lease_token = NULL,
                    lease_until = NULL,
                    last_error_code = 'APPROVAL_WINDOW_EXPIRED',
                    updated_at = CURRENT_TIMESTAMP
                FROM expired
                WHERE work.recovery_job_id = expired.recovery_job_id
                """, limit);
    }

    private void expireExhausted(int maxAttempts, long maxAgeMillis) {
        jdbc.update("""
                UPDATE payment_recovery_job
                SET status = 'NEEDS_REVIEW',
                    lease_token = NULL,
                    lease_until = NULL,
                    last_error_code = CASE
                        WHEN attempt_count >= ? THEN 'MAX_ATTEMPTS'
                        ELSE 'RECOVERY_WINDOW_EXPIRED' END,
                    updated_at = CURRENT_TIMESTAMP
                WHERE status = 'PENDING'
                  AND (lease_until IS NULL OR lease_until <= CURRENT_TIMESTAMP)
                  AND (attempt_count >= ? OR retry_window_started_at <= CURRENT_TIMESTAMP
                        - (? * INTERVAL '1 millisecond'))
                """, maxAttempts, maxAttempts, maxAgeMillis);
    }

    private boolean updateClaimed(ClaimedPaymentRecoveryJob job, String status, String errorCode) {
        Objects.requireNonNull(job);
        int updated = jdbc.update("""
                UPDATE payment_recovery_job
                SET status = ?, lease_token = NULL, lease_until = NULL,
                    last_error_code = ?, updated_at = CURRENT_TIMESTAMP
                WHERE recovery_job_id = ? AND lease_token = ? AND status = 'PENDING'
                  AND (? <> 'COMPLETED' OR work_stage = 'APPLY')
                """, status, errorCode, job.recoveryJobId(), job.leaseToken(), status);
        return updated > 0;
    }

    private static ClaimedPaymentRecoveryJob mapClaimed(ResultSet result) throws SQLException {
        PaymentRecoveryOperationType type = PaymentRecoveryOperationType.valueOf(
                result.getString("operation_type"));
        UUID operationId = type == PaymentRecoveryOperationType.PAYMENT_ATTEMPT
                ? result.getObject("payment_attempt_id", UUID.class)
                : result.getObject("payment_refund_operation_id", UUID.class);
        return new ClaimedPaymentRecoveryJob(
                result.getObject("recovery_job_id", UUID.class), type,
                PaymentRecoveryWorkStage.valueOf(result.getString("work_stage")), operationId,
                result.getInt("attempt_count"), instant(result, "first_enqueued_at"),
                result.getObject("lease_token", UUID.class));
    }

    private static PaymentRecoveryReviewRow mapReviewItem(ResultSet result) throws SQLException {
        PaymentRecoveryOperationType type = PaymentRecoveryOperationType.valueOf(
                result.getString("operation_type"));
        UUID operationId = type == PaymentRecoveryOperationType.PAYMENT_ATTEMPT
                ? result.getObject("payment_attempt_id", UUID.class)
                : result.getObject("payment_refund_operation_id", UUID.class);
        return new PaymentRecoveryReviewRow(
                result.getObject("recovery_job_id", UUID.class), type,
                PaymentRecoveryWorkStage.valueOf(result.getString("work_stage")), operationId,
                result.getObject("related_payment_attempt_id", UUID.class),
                result.getObject("related_order_id", UUID.class),
                result.getObject("related_checkout_id", UUID.class),
                result.getInt("attempt_count"), result.getInt("manual_requeue_count"),
                instant(result, "first_enqueued_at"), instant(result, "last_attempt_at"),
                result.getString("last_error_code"));
    }

    private static Instant instant(ResultSet result, String column) throws SQLException {
        Timestamp timestamp = result.getTimestamp(column);
        return timestamp == null ? null : timestamp.toInstant();
    }

    private static long nonNegativeMillis(Duration duration, String name) {
        Objects.requireNonNull(duration, name);
        if (duration.isNegative()) {
            throw new IllegalArgumentException(name + " must not be negative");
        }
        return duration.toMillis();
    }

    private static long positiveMillis(Duration duration, String name) {
        long millis = nonNegativeMillis(duration, name);
        if (millis == 0) {
            throw new IllegalArgumentException(name + " must be positive");
        }
        return millis;
    }

    private static String safeErrorCode(String errorCode) {
        if (errorCode == null || !errorCode.matches("[A-Z_]{1,64}")) {
            return "TRANSIENT_FAILURE";
        }
        return errorCode;
    }
}
