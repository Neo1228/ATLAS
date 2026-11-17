package com.atlas.application.service;

import com.atlas.application.dto.response.PaymentRecoveryRequeueResponse;
import com.atlas.application.dto.response.PaymentRecoveryReviewPage;
import com.atlas.payment.persistence.state.PaymentRecoveryJobStatus;
import com.atlas.payment.service.PaymentRecoveryService;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.Objects;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

@Service
public class PaymentRecoveryOperatorService {
    public static final int DEFAULT_PAGE_SIZE = 20;
    public static final int MAX_PAGE_SIZE = 100;
    public static final int MAX_PAGE_OFFSET = 10_000;
    public static final int MAX_MANUAL_REQUEUES = 3;
    private static final int MAX_IDEMPOTENCY_KEY_LENGTH = 128;
    private static final int MAX_ACTOR_LENGTH = 128;
    private static final int MAX_REASON_LENGTH = 500;

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final PaymentRecoveryService recovery;

    public PaymentRecoveryOperatorService(
            JdbcTemplate jdbc,
            PlatformTransactionManager transactionManager,
            PaymentRecoveryService recovery) {
        this.jdbc = Objects.requireNonNull(jdbc);
        this.transactions = new TransactionTemplate(Objects.requireNonNull(transactionManager));
        this.recovery = Objects.requireNonNull(recovery);
    }

    public PaymentRecoveryReviewPage listNeedsReview(int limit, int offset) {
        int boundedLimit = limit <= 0 ? DEFAULT_PAGE_SIZE : Math.min(limit, MAX_PAGE_SIZE);
        if (offset < 0 || offset > MAX_PAGE_OFFSET) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "recovery page offset is too large");
        }
        return new PaymentRecoveryReviewPage(
                recovery.listNeedsReview(boundedLimit, offset), boundedLimit, offset,
                recovery.count(PaymentRecoveryJobStatus.NEEDS_REVIEW));
    }

    public PaymentRecoveryRequeueResponse requeue(
            UUID recoveryJobId, String idempotencyKey, String actor, String reason) {
        validate(recoveryJobId, idempotencyKey, actor, reason);
        return Objects.requireNonNull(transactions.execute(status -> requeueInTransaction(
                recoveryJobId, idempotencyKey.trim(), actor.trim(), reason.trim())));
    }

    private PaymentRecoveryRequeueResponse requeueInTransaction(
            UUID recoveryJobId, String idempotencyKey, String actor, String reason) {
        PaymentRecoveryRequeueResponse replay = findReplay(recoveryJobId, idempotencyKey, true);
        if (replay != null) return replay;

        int updated = jdbc.update("""
                UPDATE payment_recovery_job
                SET status = 'PENDING',
                    available_at = CURRENT_TIMESTAMP,
                    retry_window_started_at = CURRENT_TIMESTAMP,
                    attempt_count = 0,
                    manual_requeue_count = manual_requeue_count + 1,
                    lease_token = NULL,
                    lease_until = NULL,
                    last_error_code = NULL,
                    updated_at = CURRENT_TIMESTAMP
                WHERE recovery_job_id = ?
                  AND status = 'NEEDS_REVIEW'
                  AND manual_requeue_count < ?
                """, recoveryJobId, MAX_MANUAL_REQUEUES);
        if (updated == 0) {
            replay = findReplay(recoveryJobId, idempotencyKey, true);
            if (replay != null) return replay;
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Recovery work is not eligible for operator requeue");
        }

        jdbc.update("""
                INSERT INTO payment_recovery_operator_action (
                    operator_action_id, recovery_job_id, idempotency_key, actor, reason, created_at
                ) VALUES (?, ?, ?, ?, ?, CURRENT_TIMESTAMP)
                """, UUID.randomUUID(), recoveryJobId, idempotencyKey, actor, reason);
        return Objects.requireNonNull(findReplay(recoveryJobId, idempotencyKey, false));
    }

    private PaymentRecoveryRequeueResponse findReplay(
            UUID recoveryJobId, String idempotencyKey, boolean duplicate) {
        var actions = jdbc.query("""
                SELECT job.status, job.manual_requeue_count, action.created_at
                FROM payment_recovery_operator_action AS action
                JOIN payment_recovery_job AS job USING (recovery_job_id)
                WHERE action.recovery_job_id = ? AND action.idempotency_key = ?
                """, (result, rowNumber) -> mapResponse(result, recoveryJobId, duplicate),
                recoveryJobId, idempotencyKey);
        return actions.isEmpty() ? null : actions.getFirst();
    }

    private static PaymentRecoveryRequeueResponse mapResponse(ResultSet result, UUID recoveryJobId, boolean duplicate)
            throws SQLException {
        Timestamp acceptedAt = result.getTimestamp("created_at");
        return new PaymentRecoveryRequeueResponse(
                recoveryJobId, result.getString("status"), result.getInt("manual_requeue_count"),
                acceptedAt == null ? null : acceptedAt.toInstant(), duplicate);
    }

    private static void validate(UUID recoveryJobId, String idempotencyKey, String actor, String reason) {
        if (recoveryJobId == null || idempotencyKey == null || idempotencyKey.isBlank()
                || idempotencyKey.length() > MAX_IDEMPOTENCY_KEY_LENGTH
                || actor == null || actor.isBlank() || actor.length() > MAX_ACTOR_LENGTH
                || reason == null || reason.isBlank() || reason.length() > MAX_REASON_LENGTH) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Recovery requeue request is invalid");
        }
    }
}
