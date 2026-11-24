package com.atlas.application.service;

import com.atlas.application.dto.response.PaymentHttpResponse;
import com.atlas.payment.dto.response.PaymentRecoveryResponse;
import com.atlas.application.exception.ApplicationException.Code;
import com.atlas.application.exception.ApplicationException;
import com.atlas.payment.model.PaymentRecoveryJobStatus;
import com.atlas.payment.service.PaymentRecoveryService;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * 결제 복구 작업의 운영자 API용 어댑터. HTTP 입력 검증과 응답 변환만 맡고,
 * {@code payment_recovery_*} 테이블의 상태 전이와 트랜잭션은 payment 모듈이 소유한다.
 */
@Service
public class PaymentRecoveryOperatorService {
    public static final int DEFAULT_PAGE_SIZE = 20;
    public static final int MAX_PAGE_SIZE = 100;
    public static final int MAX_PAGE_OFFSET = 10_000;
    public static final int MAX_MANUAL_REQUEUES = 3;
    private static final int MAX_IDEMPOTENCY_KEY_LENGTH = 128;
    private static final int MAX_ACTOR_LENGTH = 128;
    private static final int MAX_REASON_LENGTH = 500;

    private final PaymentRecoveryService recovery;

    public PaymentRecoveryOperatorService(PaymentRecoveryService recovery) {
        this.recovery = Objects.requireNonNull(recovery);
    }

    public PaymentHttpResponse.RecoveryReviewPage listNeedsReview(int limit, int offset) {
        int boundedLimit = limit <= 0 ? DEFAULT_PAGE_SIZE : Math.min(limit, MAX_PAGE_SIZE);
        if (offset < 0 || offset > MAX_PAGE_OFFSET) {
            throw new ApplicationException(Code.INVALID_REQUEST, "recovery page offset is too large");
        }
        return new PaymentHttpResponse.RecoveryReviewPage(
                recovery.listNeedsReview(boundedLimit, offset), boundedLimit, offset,
                recovery.count(PaymentRecoveryJobStatus.NEEDS_REVIEW));
    }

    public PaymentRecoveryResponse.Requeue requeue(
            UUID recoveryJobId, String idempotencyKey, String actor, String reason) {
        validate(recoveryJobId, idempotencyKey, actor, reason);
        return recovery.requeueNeedsReview(
                        recoveryJobId, idempotencyKey.trim(), actor.trim(), reason.trim(), MAX_MANUAL_REQUEUES)
                .orElseThrow(() -> new ApplicationException(Code.CONFLICT, "Recovery work is not eligible for operator requeue"));
    }

    private static void validate(UUID recoveryJobId, String idempotencyKey, String actor, String reason) {
        if (recoveryJobId == null || idempotencyKey == null || idempotencyKey.isBlank()
                || idempotencyKey.length() > MAX_IDEMPOTENCY_KEY_LENGTH
                || actor == null || actor.isBlank() || actor.length() > MAX_ACTOR_LENGTH
                || reason == null || reason.isBlank() || reason.length() > MAX_REASON_LENGTH) {
            throw new ApplicationException(Code.INVALID_REQUEST, "Recovery requeue request is invalid");
        }
    }
}
