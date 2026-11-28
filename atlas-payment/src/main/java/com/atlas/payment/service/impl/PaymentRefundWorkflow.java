package com.atlas.payment.service.impl;

import static com.atlas.payment.service.impl.PaymentPreconditions.invalidRequest;
import static com.atlas.payment.service.impl.PaymentPreconditions.notFound;
import static com.atlas.payment.service.impl.PaymentPreconditions.validateOwnerAndKey;

import com.atlas.payment.dto.response.PaymentRefundResponse;
import com.atlas.payment.exception.PaymentException;
import com.atlas.payment.persistence.entity.PaymentAttempt;
import com.atlas.payment.persistence.entity.PaymentRefundOperation;
import com.atlas.payment.model.PaymentRefundState;
import com.atlas.payment.model.PaymentState;
import com.atlas.payment.provider.PaymentProvider;
import com.atlas.payment.dto.response.PaymentRefundProviderResponse;
import com.atlas.payment.dto.request.PaymentRefundRequest;
import com.atlas.payment.repository.PaymentAttemptRepository;
import com.atlas.payment.repository.PaymentRecoveryRepository;
import com.atlas.payment.repository.PaymentRefundRepository;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.IdGenerator;

/**
 * 환불 예약·실행·재시도. 환불 요청은 결제 owner 단위로 직렬화하고, provider 호출은 transaction 밖에서 하며,
 * 결과가 모호하면 {@code UNKNOWN}으로 남겨 복구 worker가 같은 provider request ID로 이어 받는다.
 */
@Component
public final class PaymentRefundWorkflow {
    private final OwnerLockRegistry ownerLocks;
    private final PaymentAttemptRepository repository;
    private final PaymentRefundRepository refunds;
    private final PaymentRecoveryRepository recovery;
    private final TransactionTemplate transactions;
    private final PaymentProvider psp;
    private final Duration recoveryInitialDelay;
    private final IdGenerator ids;
    private final Clock clock;

    public PaymentRefundWorkflow(OwnerLockRegistry ownerLocks, PaymentAttemptRepository repository,
            PaymentRefundRepository refunds, PaymentRecoveryRepository recovery, TransactionTemplate transactions,
            PaymentProvider psp,
            @Value("${atlas.payment.recovery.initial-delay:PT30S}") Duration recoveryInitialDelay,
            IdGenerator ids, Clock clock) {
        this.ownerLocks = Objects.requireNonNull(ownerLocks);
        this.repository = Objects.requireNonNull(repository);
        this.refunds = Objects.requireNonNull(refunds);
        this.recovery = Objects.requireNonNull(recovery);
        this.transactions = Objects.requireNonNull(transactions);
        this.psp = Objects.requireNonNull(psp);
        this.recoveryInitialDelay = Objects.requireNonNull(recoveryInitialDelay);
        if (recoveryInitialDelay.isNegative()) {
            throw new IllegalArgumentException("recoveryInitialDelay must not be negative");
        }
        this.ids = Objects.requireNonNull(ids);
        this.clock = Objects.requireNonNull(clock);
    }

    void markRefundApplied(UUID refundOperationId) {
        if (refundOperationId == null) throw invalidRequest("Refund operation id is required");
        transactions.execute(status -> {
            PaymentRefundOperation operation = refunds.findByOperationIdForUpdate(refundOperationId)
                    .orElseThrow(() -> notFound("Refund operation not found"));
            if (operation.state() == PaymentRefundState.COMPLETED
                    || operation.state() == PaymentRefundState.FAILED) {
                recovery.markRefundApplied(refundOperationId);
            }
            return null;
        });
    }

    PaymentRefundResponse refundOrder(UUID orderId, String idempotencyKey, long amountWon) {
        return refund(orderId, null, idempotencyKey, amountWon);
    }

    PaymentRefundResponse refundCheckout(UUID checkoutId, String idempotencyKey, long amountWon) {
        return refund(null, checkoutId, idempotencyKey, amountWon);
    }

    PaymentRefundResponse prepareRefundOrder(UUID orderId, String idempotencyKey, long amountWon) {
        return prepareRefund(orderId, null, idempotencyKey, amountWon);
    }

    PaymentRefundResponse prepareRefundCheckout(UUID checkoutId, String idempotencyKey, long amountWon) {
        return prepareRefund(null, checkoutId, idempotencyKey, amountWon);
    }

    private PaymentRefundResponse prepareRefund(
            UUID orderId, UUID checkoutId, String idempotencyKey, long amountWon) {
        UUID ownerId = validateRefundRequest(orderId, checkoutId, idempotencyKey, amountWon);
        return withOwnerLock(ownerId, () -> {
            PaymentRefundOperation operation = reserveRefundOperation(
                    orderId, checkoutId, idempotencyKey, amountWon);
            return refundResponse(operation, operation.state(), operation.providerRefundId(), operation.updatedAt());
        });
    }

    private PaymentRefundResponse refund(UUID orderId, UUID checkoutId, String idempotencyKey, long amountWon) {
        UUID ownerId = validateRefundRequest(orderId, checkoutId, idempotencyKey, amountWon);
        return withOwnerLock(ownerId, () -> executeRefundOperation(
                reserveRefundOperation(orderId, checkoutId, idempotencyKey, amountWon)));
    }

    private UUID validateRefundRequest(UUID orderId, UUID checkoutId, String idempotencyKey, long amountWon) {
        if ((orderId == null) == (checkoutId == null)) {
            throw invalidRequest("Exactly one payment owner is required");
        }
        UUID ownerId = orderId == null ? checkoutId : orderId;
        validateOwnerAndKey(ownerId, idempotencyKey, orderId == null ? "checkoutId" : "orderId");
        if (amountWon <= 0) throw invalidRequest("Refund amount must be positive");
        return ownerId;
    }

    private PaymentRefundOperation reserveRefundOperation(
            UUID orderId, UUID checkoutId, String idempotencyKey, long amountWon) {
        PaymentAttempt attempt = Objects.requireNonNull(transactions.execute(status ->
                orderId == null ? requireCheckout(checkoutId) : requireOrder(orderId)));
        if (attempt.getState() != PaymentState.CAPTURED || attempt.getProviderCaptureId() == null
                || attempt.getTotalWon() == null
                || (psp.requiresFxSnapshot() && !attempt.hasFxSnapshot())) {
            throw invalidRequest("Only a captured PayPal payment can be refunded");
        }
        Instant now = clock.instant();
        return Objects.requireNonNull(transactions.execute(status -> {
            refunds.lockAttempt(attempt.getAttemptId());
            var existing = refunds.findByAttemptIdAndIdempotencyKeyForUpdate(
                    attempt.getAttemptId(), idempotencyKey);
            String providerCurrency = psp.requiresFxSnapshot()
                    ? attempt.getProviderCurrency() : psp.providerCurrency();
            if (providerCurrency == null || providerCurrency.isBlank()) {
                throw invalidRequest("Provider currency is unavailable for this refund");
            }
            if (existing.isPresent()) {
                PaymentRefundOperation prior = existing.get();
                if (prior.amountWon() != amountWon
                        || !Objects.equals(prior.captureId(), attempt.getProviderCaptureId())
                        || !"KRW".equals(prior.currency()) || prior.providerAmountMinor() == null
                        || !providerCurrency.equals(prior.providerCurrency())) {
                    throw invalidRequest("Idempotency key was used for different refund facts");
                }
                if (prior.state() != PaymentRefundState.COMPLETED
                        && prior.state() != PaymentRefundState.FAILED) {
                    recovery.enqueueRefund(prior.refundOperationId(), recoveryInitialDelay);
                }
                return prior;
            }
            long totalAfterRefund;
            try {
                totalAfterRefund = Math.addExact(
                        refunds.refundedAmountForAttempt(attempt.getAttemptId()), amountWon);
            } catch (ArithmeticException overflow) {
                throw invalidRequest("Refund amount is too large");
            }
            if (totalAfterRefund > attempt.getTotalWon()) {
                throw invalidRequest("Refund exceeds the captured payment amount");
            }
            if (refunds.hasUnpricedNonFailedRefunds(attempt.getAttemptId())) {
                throw invalidRequest("An existing refund must be reconciled before another refund");
            }
            long providerRefundAmount = amountWon;
            if (psp.requiresFxSnapshot()) {
                long refundedProviderAmount = refunds.refundedProviderAmountForAttempt(attempt.getAttemptId());
                long remainingProviderAmount = attempt.getProviderAmountMinor() - refundedProviderAmount;
                if (remainingProviderAmount <= 0) {
                    throw invalidRequest("Refund exceeds the remaining PayPal capture amount");
                }
                // 환불마다 따로 반올림하면 부분 환불의 센트 합이 캡처 센트와 어긋난다(1,000원 × 10회가 751센트
                // 캡처에 750센트). 누적 KRW를 한 번 반올림한 값에서 이미 보낸 센트를 빼면 합이 누적 반올림과 같다.
                // 캡처 센트도 같은 함수로 totalWon을 반올림한 값(PaymentServiceImpl)이므로 전액에 닿는 환불에서
                // 합이 캡처 금액과 정확히 같아진다(ADR-0030).
                long cumulativeProviderTarget;
                try {
                    cumulativeProviderTarget = FxConversionService.krwToUsdCents(
                            totalAfterRefund, attempt.getFxRateWonPerUsd());
                } catch (IllegalArgumentException belowOneCent) {
                    throw invalidRequest("Refund amount is below the PayPal minimum");
                }
                providerRefundAmount = Math.min(cumulativeProviderTarget - refundedProviderAmount,
                        remainingProviderAmount);
                if (providerRefundAmount <= 0) {
                    throw invalidRequest("Refund amount is below the PayPal minimum");
                }
            }
            PaymentRefundOperation created = refunds.reserve(ids.generateId(), attempt.getAttemptId(),
                    attempt.getProviderCaptureId(), idempotencyKey, amountWon,
                    providerRefundAmount, providerCurrency, ids.generateId(), now);
            recovery.enqueueRefund(created.refundOperationId(), recoveryInitialDelay);
            return created;
        }));
    }

    PaymentRefundResponse retryRefundOperation(UUID refundOperationId) {
        if (refundOperationId == null) throw invalidRequest("Refund operation id is required");
        PaymentRefundOperation loaded = Objects.requireNonNull(transactions.execute(status ->
                refunds.findByOperationIdForUpdate(refundOperationId)
                        .orElseThrow(() -> notFound("Refund operation not found"))));
        PaymentAttempt attempt = Objects.requireNonNull(transactions.execute(status ->
                repository.findWithLockByAttemptId(loaded.attemptId())
                        .orElseThrow(() -> notFound("Payment attempt for refund was not found"))));
        UUID ownerId = attempt.getCheckoutId() == null ? attempt.getOrderId() : attempt.getCheckoutId();
        return withOwnerLock(ownerId, () -> {
            PaymentRefundOperation current = Objects.requireNonNull(transactions.execute(status ->
                    refunds.findByOperationIdForUpdate(refundOperationId)
                            .orElseThrow(() -> notFound("Refund operation not found"))));
            return executeRefundOperation(current);
        });
    }

    private PaymentRefundResponse executeRefundOperation(PaymentRefundOperation operation) {
        if (operation.state() == PaymentRefundState.COMPLETED || operation.state() == PaymentRefundState.FAILED) {
            recovery.markRefundForApplication(operation.refundOperationId());
            return refundResponse(operation, operation.state(), operation.providerRefundId(), operation.updatedAt());
        }
        PaymentRefundProviderResponse result;
        try {
            result = operation.providerRefundId() == null
                    ? psp.refund(new PaymentRefundRequest(operation.captureId(), operation.providerAmountMinor(),
                            operation.providerCurrency(), operation.providerRequestId().toString(),
                            operation.createdAt()))
                    : psp.getRefund(operation.providerRefundId());
        } catch (PaymentException exception) {
            if (exception.code() != PaymentException.Code.PAYMENT_PROVIDER_UNAVAILABLE) throw exception;
            result = new PaymentRefundProviderResponse(PaymentRefundProviderResponse.Outcome.UNKNOWN,
                    operation.providerRefundId(), operation.providerStatus());
        }
        if (result.outcome() != PaymentRefundProviderResponse.Outcome.FAILED
                && (result.amount() == null
                || result.amount().compareTo(BigDecimal.valueOf(operation.providerAmountMinor(), 2)) != 0
                || !operation.providerCurrency().equalsIgnoreCase(result.currency()))) {
            result = new PaymentRefundProviderResponse(PaymentRefundProviderResponse.Outcome.UNKNOWN,
                    result.refundId(), result.providerStatus(), result.amount(), result.currency());
        }
        PaymentRefundProviderResponse recordedResult = result;
        PaymentRefundState state = switch (result.outcome()) {
            case COMPLETED -> PaymentRefundState.COMPLETED;
            case PENDING -> PaymentRefundState.PENDING;
            case FAILED -> PaymentRefundState.FAILED;
            case UNKNOWN -> PaymentRefundState.UNKNOWN;
        };
        Instant updatedAt = clock.instant();
        PaymentRefundOperation persisted = Objects.requireNonNull(transactions.execute(status -> {
            refunds.updateResult(operation.refundOperationId(), state, recordedResult.refundId(),
                    recordedResult.providerStatus(), updatedAt);
            PaymentRefundOperation latest = refunds.findByOperationIdForUpdate(operation.refundOperationId())
                    .orElseThrow(() -> notFound("Refund operation not found"));
            if (latest.state() == PaymentRefundState.COMPLETED || latest.state() == PaymentRefundState.FAILED) {
                recovery.markRefundForApplication(operation.refundOperationId());
            }
            return latest;
        }));
        return refundResponse(persisted, persisted.state(), persisted.providerRefundId(), persisted.updatedAt());
    }

    PaymentRefundResponse findRefundOperation(UUID refundOperationId) {
        if (refundOperationId == null) throw invalidRequest("Refund operation id is required");
        PaymentRefundOperation operation = Objects.requireNonNull(transactions.execute(status ->
                refunds.findByOperationIdForUpdate(refundOperationId)
                        .orElseThrow(() -> new PaymentException(PaymentException.Code.ATTEMPT_NOT_FOUND,
                                "Refund operation not found"))));
        return refundResponse(operation, operation.state(), operation.providerRefundId(), operation.updatedAt());
    }

    private static PaymentRefundResponse refundResponse(PaymentRefundOperation operation, PaymentRefundState state,
            String providerRefundId, Instant updatedAt) {
        return new PaymentRefundResponse(operation.refundOperationId(), operation.attemptId(), operation.captureId(),
                operation.amountWon(), operation.currency(), providerRefundId, state, updatedAt);
    }

    private <T> T withOwnerLock(UUID ownerId, Supplier<T> action) {
        return ownerLocks.withLock(ownerId, action);
    }

    private PaymentAttempt requireOrder(UUID orderId) {
        return PaymentPreconditions.requireOrder(repository, orderId);
    }

    private PaymentAttempt requireCheckout(UUID checkoutId) {
        return PaymentPreconditions.requireCheckout(repository, checkoutId);
    }
}
