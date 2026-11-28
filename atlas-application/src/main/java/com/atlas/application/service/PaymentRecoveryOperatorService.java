package com.atlas.application.service;

import com.atlas.application.dto.request.PaymentHttpRequest;
import com.atlas.application.dto.response.PaymentHttpResponse;
import com.atlas.order.dto.response.RefundResponse;
import com.atlas.order.service.OrderRefundService;
import com.atlas.payment.dto.response.PaymentRecoveryResponse;
import com.atlas.application.exception.ApplicationException.Code;
import com.atlas.application.exception.ApplicationException;
import com.atlas.payment.model.PaymentRecoveryJobStatus;
import com.atlas.payment.service.PaymentRecoveryService;
import com.atlas.payment.service.PaymentService;
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
    private final PaymentService payments;
    private final OrderRefundService orderRefunds;

    public PaymentRecoveryOperatorService(PaymentRecoveryService recovery, PaymentService payments,
            OrderRefundService orderRefunds) {
        this.recovery = Objects.requireNonNull(recovery);
        this.payments = Objects.requireNonNull(payments);
        this.orderRefunds = Objects.requireNonNull(orderRefunds);
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

    /**
     * 재전송 기한이 지나 {@code NEEDS_REVIEW}로 멈춘 환불을 운영자가 PayPal에서 확인한 결과로 닫는다(ADR-0108).
     *
     * <p>payment 모듈이 상태와 감사 행을 한 트랜잭션에 쓰고 작업을 적용 단계로 넘긴 뒤, 웹훅·복구 worker가 확정을
     * 반영할 때와 같은 {@code reconcilePayPalRefund}로 원장·주문 환불 상태를 맞춘다(완료는 환불 분개, 실패는 예약 해제).
     * 그 반영이 여기서 실패해도 작업이 적용 단계로 남아 복구 worker가 다시 반영하고, 같은 요청을 다시 보내도 된다.
     */
    public RefundResponse.Refund resolveExpiredRefund(UUID refundOperationId, String actor,
            PaymentHttpRequest.ExpiredRefundResolution request) {
        if (request == null) throw new ApplicationException(Code.INVALID_REQUEST, "Refund resolution is required");
        payments.resolveExpiredRefund(refundOperationId, request.outcome(), request.providerRefundId(), actor);
        return orderRefunds.reconcilePayPalRefund(refundOperationId);
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
