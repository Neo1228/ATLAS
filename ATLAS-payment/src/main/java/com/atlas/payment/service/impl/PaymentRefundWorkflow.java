package com.atlas.payment.service.impl;

import static com.atlas.payment.service.impl.PaymentPreconditions.invalidRequest;
import static com.atlas.payment.service.impl.PaymentPreconditions.notFound;
import static com.atlas.payment.service.impl.PaymentPreconditions.validateOwnerAndKey;

import com.atlas.payment.dto.response.PaymentRefundResponse;
import com.atlas.payment.exception.PaymentException;
import com.atlas.payment.persistence.entity.PaymentAttempt;
import com.atlas.payment.persistence.entity.PaymentRefundOperation;
import com.atlas.payment.persistence.state.PaymentRefundState;
import com.atlas.payment.persistence.state.PaymentState;
import com.atlas.payment.provider.PaymentProvider;
import com.atlas.payment.provider.PaymentRefundProviderResult;
import com.atlas.payment.provider.PaymentRefundRequest;
import com.atlas.payment.repository.PaymentAttemptRepository;
import com.atlas.payment.repository.PaymentRecoveryRepository;
import com.atlas.payment.repository.PaymentRefundRepository;
import com.atlas.payment.service.FxConversionService;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 환불 예약·실행·재시도. 환불 요청은 결제 owner 단위로 직렬화하고, provider 호출은 transaction 밖에서 하며,
 * 결과가 모호하면 {@code UNKNOWN}으로 남겨 복구 worker가 같은 provider request ID로 이어 받는다.
 */
final class PaymentRefundWorkflow {
    private final OwnerLockRegistry ownerLocks;
    private final PaymentAttemptRepository repository;
    private final PaymentRefundRepository refunds;
    private final PaymentRecoveryRepository recovery;
    private final TransactionTemplate transactions;
    private final PaymentProvider psp;
    private final Duration recoveryInitialDelay;
    private final Supplier<UUID> ids;

    PaymentRefundWorkflow(OwnerLockRegistry ownerLocks, PaymentAttemptRepository repository,
            PaymentRefundRepository refunds, PaymentRecoveryRepository recovery, TransactionTemplate transactions,
            PaymentProvider psp, Duration recoveryInitialDelay, Supplier<UUID> ids) {
        this.ownerLocks = Objects.requireNonNull(ownerLocks);
        this.repository = Objects.requireNonNull(repository);
        this.refunds = Objects.requireNonNull(refunds);
        this.recovery = Objects.requireNonNull(recovery);
        this.transactions = Objects.requireNonNull(transactions);
        this.psp = Objects.requireNonNull(psp);
        this.recoveryInitialDelay = Objects.requireNonNull(recoveryInitialDelay);
        this.ids = Objects.requireNonNull(ids);
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
        Instant now = Instant.now();
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
                long remainingProviderAmount = attempt.getProviderAmountMinor()
                        - refunds.refundedProviderAmountForAttempt(attempt.getAttemptId());
                if (remainingProviderAmount <= 0) {
                    throw invalidRequest("Refund exceeds the remaining PayPal capture amount");
                }
                providerRefundAmount = Math.min(
                        FxConversionService.krwToUsdCents(amountWon, attempt.getFxRateWonPerUsd()),
                        remainingProviderAmount);
                if (providerRefundAmount <= 0) {
                    throw invalidRequest("Refund amount is below the PayPal minimum");
                }
            }
            PaymentRefundOperation created = refunds.reserve(ids.get(), attempt.getAttemptId(),
                    attempt.getProviderCaptureId(), idempotencyKey, amountWon,
                    providerRefundAmount, providerCurrency, ids.get(), now);
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
        PaymentRefundProviderResult result;
        try {
            result = operation.providerRefundId() == null
                    ? psp.refund(new PaymentRefundRequest(operation.captureId(), operation.providerAmountMinor(),
                            operation.providerCurrency(), operation.providerRequestId().toString()))
                    : psp.getRefund(operation.providerRefundId());
        } catch (PaymentException exception) {
            if (exception.code() != PaymentException.Code.PAYMENT_PROVIDER_UNAVAILABLE) throw exception;
            result = new PaymentRefundProviderResult(PaymentRefundProviderResult.Outcome.UNKNOWN,
                    operation.providerRefundId(), operation.providerStatus());
        }
        if (result.outcome() != PaymentRefundProviderResult.Outcome.FAILED
                && (result.amount() == null
                || result.amount().compareTo(BigDecimal.valueOf(operation.providerAmountMinor(), 2)) != 0
                || !operation.providerCurrency().equalsIgnoreCase(result.currency()))) {
            result = new PaymentRefundProviderResult(PaymentRefundProviderResult.Outcome.UNKNOWN,
                    result.refundId(), result.providerStatus(), result.amount(), result.currency());
        }
        PaymentRefundProviderResult recordedResult = result;
        PaymentRefundState state = switch (result.outcome()) {
            case COMPLETED -> PaymentRefundState.COMPLETED;
            case PENDING -> PaymentRefundState.PENDING;
            case FAILED -> PaymentRefundState.FAILED;
            case UNKNOWN -> PaymentRefundState.UNKNOWN;
        };
        Instant updatedAt = Instant.now();
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
