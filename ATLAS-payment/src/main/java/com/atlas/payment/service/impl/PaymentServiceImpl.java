package com.atlas.payment.service.impl;

import com.atlas.payment.provider.PaymentProviderResult;
import com.atlas.payment.provider.PaymentOperation;
import com.atlas.payment.provider.PaymentRequest;
import com.atlas.payment.persistence.state.PaymentPhase;
import com.atlas.payment.service.PaymentService;
import com.atlas.payment.persistence.state.PaymentState;
import com.atlas.payment.exception.PaymentException;
import com.atlas.payment.dto.response.PaymentResponse;
import com.atlas.payment.persistence.entity.PaymentAttempt;
import com.atlas.payment.provider.PaymentProvider;
import com.atlas.payment.service.FxConversionService;
import com.atlas.payment.repository.PaymentAttemptRepository;
import com.atlas.payment.dto.response.PaymentRefundResponse;
import com.atlas.payment.dto.response.PaymentWebhookResult;
import com.atlas.payment.persistence.entity.PaymentRefundOperation;
import com.atlas.payment.persistence.state.PaymentRefundState;
import com.atlas.payment.provider.PaymentRefundProviderResult;
import com.atlas.payment.provider.PaymentRefundRequest;
import com.atlas.payment.provider.PaymentWebhookEvent;
import com.atlas.payment.provider.PaymentWebhookSignature;
import com.atlas.payment.repository.PaymentRefundRepository;
import com.atlas.payment.repository.PaymentWebhookEventRepository;
import java.time.Instant;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class PaymentServiceImpl implements PaymentService {

    private final OwnerLockRegistry ownerLocks = new OwnerLockRegistry();

    private final PaymentAttemptRepository repository;
    private final PaymentRefundRepository refunds;
    private final PaymentWebhookEventRepository webhookEvents;
    private final TransactionTemplate transactions;
    private final PaymentProvider psp;
    private final FxConversionService fxConversionService;
    private final String publicBaseUrl;
    private final Supplier<UUID> ids;

    @Autowired
    public PaymentServiceImpl(PaymentAttemptRepository repository, PaymentRefundRepository refunds,
            PaymentWebhookEventRepository webhookEvents, PlatformTransactionManager transactionManager,
            PaymentProvider psp, FxConversionService fxConversionService,
            @Value("${ATLAS_PUBLIC_BASE_URL:http://127.0.0.1:8080}") String publicBaseUrl) {
        this(repository, refunds, webhookEvents, transactionManager, psp, fxConversionService,
                publicBaseUrl, UUID::randomUUID);
    }

    PaymentServiceImpl(PaymentAttemptRepository repository, PaymentRefundRepository refunds,
            PaymentWebhookEventRepository webhookEvents, PlatformTransactionManager transactionManager,
            PaymentProvider psp, FxConversionService fxConversionService,
            String publicBaseUrl, Supplier<UUID> ids) {
        this.repository = Objects.requireNonNull(repository);
        this.refunds = Objects.requireNonNull(refunds);
        this.webhookEvents = Objects.requireNonNull(webhookEvents);
        this.transactions = new TransactionTemplate(Objects.requireNonNull(transactionManager));
        this.psp = Objects.requireNonNull(psp);
        this.fxConversionService = Objects.requireNonNull(fxConversionService);
        String baseUrl = Objects.requireNonNull(publicBaseUrl).trim();
        if (baseUrl.isBlank()) throw new IllegalStateException("ATLAS_PUBLIC_BASE_URL is required");
        this.publicBaseUrl = baseUrl.replaceAll("/+$", "");
        this.ids = Objects.requireNonNull(ids);
    }
    @Override
    public PaymentResponse submitInitial(UUID orderId, BigDecimal amount) {
        if (orderId == null) throw invalidRequest("orderId required");
        long amountWon = amountWon(amount);
        return withOwnerLock(orderId, () -> {
            PaymentAttempt attempt = transactions.execute(status -> {
                repository.insertInitial(orderId, amountWon, ids.get());
                return requireOrder(orderId);
            });
            if (attempt.getState() != PaymentState.NOT_STARTED) return toResponse(attempt);
            if (attempt.getTotalWon() == null || attempt.getTotalWon() != amountWon) {
                throw invalidRequest("PayPal amount does not match the stored payment attempt");
            }
            return performPhase(attempt, "initial:" + attempt.getAttemptId());
        });
    }

    @Override
    public PaymentResponse submitCheckout(UUID checkoutId, BigDecimal amount) {
        if (checkoutId == null) throw invalidRequest("checkoutId required");
        long amountWon = amountWon(amount);
        return withOwnerLock(checkoutId, () -> {
            PaymentAttempt attempt = transactions.execute(status -> {
                repository.insertCheckoutInitial(checkoutId, amountWon, ids.get());
                return requireCheckout(checkoutId);
            });
            if (attempt.getState() != PaymentState.NOT_STARTED) return toResponse(attempt);
            if (attempt.getTotalWon() == null || attempt.getTotalWon() != amountWon) {
                throw invalidRequest("PayPal amount does not match the stored payment attempt");
            }
            return performPhase(attempt, "initial:" + attempt.getAttemptId());
        });
    }

    @Override
    public PaymentResponse completeApproval(UUID orderId, String providerOrderId) {
        return completeApprovalForOwner(orderId, null, providerOrderId);
    }

    @Override
    public PaymentResponse completeCheckoutApproval(UUID checkoutId, String providerOrderId) {
        return completeApprovalForOwner(null, checkoutId, providerOrderId);
    }

    private PaymentResponse completeApprovalForOwner(UUID orderId, UUID checkoutId,
            String providerOrderId) {
        if ((orderId == null) == (checkoutId == null) || providerOrderId == null
                || providerOrderId.isBlank()) throw invalidRequest("A provider order ID is required");
        UUID ownerId = orderId == null ? checkoutId : orderId;
        return withOwnerLock(ownerId, () -> {
            PhaseStart start = transactions.execute(status -> {
                PaymentAttempt current = orderId == null
                        ? repository.findWithLockByCheckoutId(checkoutId)
                                .orElseThrow(() -> notFound("No payment attempt for checkout: " + checkoutId))
                        : repository.findWithLockByOrderId(orderId)
                                .orElseThrow(() -> notFound("No payment attempt for order: " + orderId));
                if (!Objects.equals(current.getProviderOrderId(), providerOrderId)) {
                    throw invalidRequest("PayPal approval does not match the payment attempt");
                }
                if (current.getPaymentPhase() != PaymentPhase.AUTHORIZATION
                        || current.getState() != PaymentState.AUTHORIZATION_PENDING) {
                    return new PhaseStart(current, null, PaymentPhase.AUTHORIZATION, null);
                }
                PaymentRequest request = providerRequest(current, PaymentPhase.AUTHORIZATION);
                repository.save(current);
                return new PhaseStart(current, request, PaymentPhase.AUTHORIZATION,
                        phaseRequestId(current, PaymentPhase.AUTHORIZATION));
            });
            if (start.request() == null) return toResponse(start.attempt());
            PaymentProviderResult result;
            try {
                result = psp.completeApproval(start.request(), providerOrderId);
            } catch (PaymentException exception) {
                if (exception.code() != PaymentException.Code.PAYMENT_PROVIDER_UNAVAILABLE) throw exception;
                return toResponse(finalizePhase(start, null, PaymentProviderResult.Outcome.UNKNOWN));
            }
            return toResponse(finalizePhase(start, result, result.outcome()));
        });
    }
    @Override
    public PaymentResponse reconcile(UUID orderId, String idempotencyKey) {
        validateOwnerAndKey(orderId, idempotencyKey, "orderId");
        return withOwnerLock(orderId, () -> {
            PaymentAttempt attempt = transactions.execute(status -> requireOrder(orderId));
            if (terminal(attempt.getState())) return toResponse(attempt);
            return performPhase(attempt, idempotencyKey);
        });
    }

    @Override
    public PaymentResponse reconcileCheckout(UUID checkoutId, String idempotencyKey) {
        validateOwnerAndKey(checkoutId, idempotencyKey, "checkoutId");
        return withOwnerLock(checkoutId, () -> {
            PaymentAttempt attempt = transactions.execute(status -> requireCheckout(checkoutId));
            if (terminal(attempt.getState())) return toResponse(attempt);
            return performPhase(attempt, idempotencyKey);
        });
    }

    @Override
    public PaymentResponse reconcileForCancellation(UUID orderId, String idempotencyKey) {
        validateOwnerAndKey(orderId, idempotencyKey, "orderId");
        return withOwnerLock(orderId, () -> {
            PaymentAttempt attempt = transactions.execute(status -> requireOrder(orderId));
            if (terminal(attempt.getState())) return toResponse(attempt);
            String phaseKey = attempt.getPaymentPhase() == PaymentPhase.VOID
                    ? idempotencyKey + ":void" : idempotencyKey + ":cancel-reconcile";
            return performPhase(attempt, phaseKey, false);
        });
    }

    @Override
    public PaymentResponse reconcileCheckoutForCancellation(UUID checkoutId, String idempotencyKey) {
        validateOwnerAndKey(checkoutId, idempotencyKey, "checkoutId");
        return withOwnerLock(checkoutId, () -> {
            PaymentAttempt attempt = transactions.execute(status -> requireCheckout(checkoutId));
            if (terminal(attempt.getState())) return toResponse(attempt);
            String phaseKey = attempt.getPaymentPhase() == PaymentPhase.VOID
                    ? idempotencyKey + ":void" : idempotencyKey + ":cancel-reconcile";
            return performPhase(attempt, phaseKey, false);
        });
    }

    @Override
    public PaymentResponse voidAuthorization(UUID orderId, String idempotencyKey) {
        validateOwnerAndKey(orderId, idempotencyKey, "orderId");
        return withOwnerLock(orderId, () -> voidAuthorizationForOwner(orderId, null, idempotencyKey));
    }

    @Override
    public PaymentResponse voidCheckoutAuthorization(UUID checkoutId, String idempotencyKey) {
        validateOwnerAndKey(checkoutId, idempotencyKey, "checkoutId");
        return withOwnerLock(checkoutId, () -> voidAuthorizationForOwner(null, checkoutId, idempotencyKey));
    }

    private PaymentResponse voidAuthorizationForOwner(UUID orderId, UUID checkoutId, String idempotencyKey) {
        PaymentAttempt attempt = transactions.execute(status -> {
            PaymentAttempt current = orderId == null
                    ? repository.findWithLockByCheckoutId(checkoutId)
                            .orElseThrow(() -> notFound("No payment attempt for checkout: " + checkoutId))
                    : repository.findWithLockByOrderId(orderId)
                            .orElseThrow(() -> notFound("No payment attempt for order: " + orderId));
            if (!current.isAuthorizationActive() || current.getState() == PaymentState.VOIDED) {
                return current;
            }
            if (current.getState() == PaymentState.AUTHORIZED || current.getState() == PaymentState.DECLINED) {
                current.setState(PaymentState.VOID_PENDING);
                current.setPaymentPhase(PaymentPhase.VOID);
                current.setAuthorizationActive(true);
                repository.save(current);
            }
            return current;
        });
        if (attempt.getPaymentPhase() != PaymentPhase.VOID
                || (attempt.getState() != PaymentState.VOID_PENDING
                && attempt.getState() != PaymentState.UNKNOWN)) {
            return toResponse(attempt);
        }
        return performPhase(attempt, idempotencyKey);
    }
    @Override

    public PaymentResponse find(UUID orderId) {
        if (orderId == null) throw invalidRequest("orderId required");
        PaymentAttempt attempt = repository.findByOrderId(orderId).orElse(null);
        return attempt == null ? null : toResponse(attempt);
    }

    @Override

    public PaymentResponse findCheckout(UUID checkoutId) {
        if (checkoutId == null) throw invalidRequest("checkoutId required");
        PaymentAttempt attempt = repository.findByCheckoutId(checkoutId).orElse(null);
        return attempt == null ? null : toResponse(attempt);
    }

    @Override
    public PaymentResponse findByAttemptId(UUID attemptId) {
        if (attemptId == null) throw invalidRequest("attemptId required");
        PaymentAttempt attempt = repository.findById(attemptId).orElse(null);
        return attempt == null ? null : toResponse(attempt);
    }

    @Override
    public PaymentRefundResponse refundOrder(UUID orderId, String idempotencyKey, long amountWon) {
        return refund(orderId, null, idempotencyKey, amountWon);
    }

    @Override
    public PaymentRefundResponse refundCheckout(UUID checkoutId, String idempotencyKey, long amountWon) {
        return refund(null, checkoutId, idempotencyKey, amountWon);
    }

    private PaymentRefundResponse refund(UUID orderId, UUID checkoutId, String idempotencyKey, long amountWon) {
        if ((orderId == null) == (checkoutId == null)) throw invalidRequest("Exactly one payment owner is required");
        UUID ownerId = orderId == null ? checkoutId : orderId;
        validateOwnerAndKey(ownerId, idempotencyKey, orderId == null ? "checkoutId" : "orderId");
        if (amountWon <= 0) throw invalidRequest("Refund amount must be positive");
        return withOwnerLock(ownerId, () -> {
            PaymentAttempt attempt = Objects.requireNonNull(transactions.execute(status ->
                    orderId == null ? requireCheckout(checkoutId) : requireOrder(orderId)));
            if (attempt.getState() != PaymentState.CAPTURED || attempt.getProviderCaptureId() == null
                    || attempt.getTotalWon() == null
                    || (psp.requiresFxSnapshot() && !attempt.hasFxSnapshot())) {
                throw invalidRequest("Only a captured PayPal payment can be refunded");
            }
            Instant now = Instant.now();
            PaymentRefundOperation operation = Objects.requireNonNull(transactions.execute(status -> {
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
                    return prior;
                }
                long totalAfterRefund;
                try {
                    totalAfterRefund = Math.addExact(refunds.refundedAmountForAttempt(attempt.getAttemptId()), amountWon);
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
                return refunds.reserve(ids.get(), attempt.getAttemptId(), attempt.getProviderCaptureId(),
                        idempotencyKey, amountWon, providerRefundAmount, providerCurrency, ids.get(), now);
            }));
            if (operation.state() == PaymentRefundState.COMPLETED || operation.state() == PaymentRefundState.FAILED) {
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
            PaymentRefundState state = switch (result.outcome()) {
                case COMPLETED -> PaymentRefundState.COMPLETED;
                case PENDING -> PaymentRefundState.PENDING;
                case FAILED -> PaymentRefundState.FAILED;
                case UNKNOWN -> PaymentRefundState.UNKNOWN;
            };
            Instant updatedAt = Instant.now();
            refunds.updateResult(operation.refundOperationId(), state, result.refundId(), result.providerStatus(), updatedAt);
            return refundResponse(operation, state, result.refundId(), updatedAt);
        });
    }

    @Override
    public PaymentRefundResponse findRefundOperation(UUID refundOperationId) {
        if (refundOperationId == null) throw invalidRequest("Refund operation id is required");
        PaymentRefundOperation operation = Objects.requireNonNull(transactions.execute(status ->
                refunds.findByOperationIdForUpdate(refundOperationId)
                        .orElseThrow(() -> new PaymentException(PaymentException.Code.ATTEMPT_NOT_FOUND,
                                "Refund operation not found"))));
        return refundResponse(operation, operation.state(), operation.providerRefundId(), operation.updatedAt());
    }

    @Override
    public PaymentWebhookEvent verifyPayPalWebhook(PaymentWebhookSignature signature, String rawBody) {
        if (signature == null || rawBody == null || rawBody.isBlank()) {
            throw invalidRequest("PayPal webhook signature and body are required");
        }
        return psp.verifyWebhook(signature, rawBody);
    }

    @Override
    public PaymentWebhookResult processVerifiedPayPalWebhook(PaymentWebhookEvent event) {
        if (event == null) throw invalidRequest("Verified PayPal webhook event is required");
        Instant now = Instant.now();
        return Objects.requireNonNull(transactions.execute(status -> {
            if ("PAYMENT.CAPTURE.COMPLETED".equals(event.eventType())) {
                Optional<PaymentAttempt> attempt = event.providerOrderId() == null
                        ? Optional.empty() : repository.findWithLockByProviderOrderId(event.providerOrderId());
                if (attempt.isEmpty() && event.resourceId() != null) {
                    attempt = repository.findWithLockByProviderCaptureId(event.resourceId());
                }
                if (attempt.isEmpty()) {
                    return new PaymentWebhookResult(false, false, null, null, null, false, true);
                }
                PaymentAttempt current = attempt.get();
                if (!"COMPLETED".equalsIgnoreCase(event.resourceStatus())
                        || event.resourceAmount() == null || current.getTotalWon() == null
                        || !current.hasFxSnapshot()
                        || event.resourceAmount().compareTo(
                                BigDecimal.valueOf(current.getProviderAmountMinor(), 2)) != 0
                        || !current.getProviderCurrency().equalsIgnoreCase(event.resourceCurrency())
                        || event.resourceId() == null
                        || (event.providerOrderId() != null
                        && !event.providerOrderId().equals(current.getProviderOrderId()))
                        || (current.getProviderCaptureId() != null
                        && !event.resourceId().equals(current.getProviderCaptureId()))) {
                    return rejectedWebhook(event, now);
                }
                if (!webhookEvents.insertReceived(event, now)) {
                    return new PaymentWebhookResult(true, true, null, null, null, false);
                }
                current.setProviderCaptureId(event.resourceId());
                current.setState(PaymentState.CAPTURED);
                current.setPaymentPhase(PaymentPhase.CAPTURE);
                current.setAuthorizationActive(false);
                repository.save(current);
                webhookEvents.markProcessed(event.eventId(), now);
                return new PaymentWebhookResult(false, true, null, null, current.getAttemptId(), true);
            }

            PaymentRefundState refundState = refundWebhookState(event.eventType());
            if (refundState != null) {
                if (event.resourceId() == null) {
                    return new PaymentWebhookResult(false, false, null, null, null, false, true);
                }
                var operation = refunds.findByProviderRefundIdForUpdate(event.resourceId());
                if (operation.isEmpty()) {
                    return new PaymentWebhookResult(false, false, null, null, null, false, true);
                }
                PaymentRefundOperation current = operation.get();
                String expectedStatus = switch (refundState) {
                    case COMPLETED -> "COMPLETED";
                    case PENDING, UNKNOWN -> "PENDING";
                    case FAILED -> "FAILED";
                };
                if (event.resourceAmount() == null || current.providerAmountMinor() == null
                        || event.resourceAmount().compareTo(
                                BigDecimal.valueOf(current.providerAmountMinor(), 2)) != 0
                        || current.providerCurrency() == null
                        || !current.providerCurrency().equalsIgnoreCase(event.resourceCurrency())
                        || !expectedStatus.equalsIgnoreCase(event.resourceStatus())
                        || (event.relatedCaptureId() != null
                        && !current.captureId().equals(event.relatedCaptureId()))) {
                    return rejectedWebhook(event, now);
                }
                if (!webhookEvents.insertReceived(event, now)) {
                    return new PaymentWebhookResult(true, true, null, null, null, false);
                }
                refunds.updateResult(current.refundOperationId(), refundState, event.resourceId(),
                        event.resourceStatus(), now);
                webhookEvents.markProcessed(event.eventId(), now);
                return new PaymentWebhookResult(false, true, current.refundOperationId(),
                        refundState, null, false);
            }

            if (!webhookEvents.insertReceived(event, now)) {
                return new PaymentWebhookResult(true, true, null, null, null, false);
            }
            webhookEvents.markProcessed(event.eventId(), now);
            return new PaymentWebhookResult(false, true, null, null, null, false);
        }));
    }
    private PaymentWebhookResult rejectedWebhook(PaymentWebhookEvent event, Instant now) {
        if (!webhookEvents.insertReceived(event, now)) {
            return new PaymentWebhookResult(true, true, null, null, null, false);
        }
        webhookEvents.markFailed(event.eventId(), now);
        return new PaymentWebhookResult(false, false, null, null, null, false);
    }


    private static PaymentRefundState refundWebhookState(String eventType) {
        return switch (eventType) {
            case "PAYMENT.REFUND.COMPLETED", "PAYMENT.CAPTURE.REFUNDED" -> PaymentRefundState.COMPLETED;
            case "PAYMENT.REFUND.PENDING" -> PaymentRefundState.PENDING;
            case "PAYMENT.REFUND.FAILED" -> PaymentRefundState.FAILED;
            default -> null;
        };
    }

    private static PaymentRefundResponse refundResponse(PaymentRefundOperation operation, PaymentRefundState state,
            String providerRefundId, Instant updatedAt) {
        return new PaymentRefundResponse(operation.refundOperationId(), operation.attemptId(), operation.captureId(),
                operation.amountWon(), operation.currency(), providerRefundId, state, updatedAt);
    }

    private void ensureFxSnapshot(UUID attemptId) {
        if (!psp.requiresFxSnapshot()) return;
        PaymentAttempt attempt = Objects.requireNonNull(transactions.execute(status ->
                repository.findById(attemptId).orElseThrow(() -> notFound("No payment attempt: " + attemptId))));
        if (attempt.hasFxSnapshot()) return;
        if (attempt.getPaymentPhase() != PaymentPhase.AUTHORIZATION || attempt.getProviderOrderId() != null
                || attempt.getTotalWon() == null || attempt.getTotalWon() <= 0) {
            throw invalidRequest("A saved FX snapshot is required for this PayPal operation");
        }

        FxConversionService.Conversion conversion;
        try {
            conversion = fxConversionService.convertKrwToUsdCents(attempt.getTotalWon());
        } catch (IllegalArgumentException | ArithmeticException invalidAmount) {
            throw invalidRequest("PayPal 결제 금액이 허용 범위를 벗어났습니다");
        }
        long providerAmount = conversion.usdCents();
        if (providerAmount <= 0) throw invalidRequest("Payment amount is below the PayPal minimum");
        transactions.execute(status -> {
            PaymentAttempt current = repository.findWithLockByAttemptId(attemptId)
                    .orElseThrow(() -> notFound("No payment attempt: " + attemptId));
            if (!current.hasFxSnapshot()) {
                if (!Objects.equals(current.getTotalWon(), attempt.getTotalWon())) {
                    throw invalidRequest("Payment amount changed while preparing the FX snapshot");
                }
                current.setFxSnapshot(conversion.quote().wonPerUsd(), conversion.quote().source(),
                        conversion.quote().asOfDate(), providerAmount, "USD");
                repository.save(current);
            }
            return null;
        });
    }


    private record PhaseStart(PaymentAttempt attempt, PaymentRequest request,
            PaymentPhase phase, String requestId) {}

    private PhaseStart reservePhase(UUID attemptId, PaymentPhase phase, boolean continueCapture) {
        return transactions.execute(status -> {
            PaymentAttempt current = repository.findWithLockByAttemptId(attemptId)
                    .orElseThrow(() -> notFound("No payment attempt: " + attemptId));
            if (current.getPaymentPhase() != phase || terminal(current.getState())
                    || (!continueCapture && phase == PaymentPhase.CAPTURE
                    && current.getState() == PaymentState.AUTHORIZED)
                    || !canExecutePhase(current, phase)) {
                return new PhaseStart(current, null, phase, null);
            }
            switch (phase) {
                case AUTHORIZATION -> {
                    if (current.getState() == PaymentState.NOT_STARTED) {
                        current.setState(PaymentState.AUTHORIZATION_PENDING);
                    }
                    current.setAuthorizationActive(true);
                }
                case CAPTURE -> {
                    current.setState(PaymentState.CAPTURE_PENDING);
                    current.setAuthorizationActive(true);
                }
                case VOID -> {
                    current.setState(PaymentState.VOID_PENDING);
                    current.setAuthorizationActive(true);
                }
            }
            PaymentRequest request = providerRequest(current, phase);
            String requestId = phaseRequestId(current, phase);
            repository.save(current);
            return new PhaseStart(current, request, phase, requestId);
        });
    }

    private static boolean canExecutePhase(PaymentAttempt attempt, PaymentPhase phase) {
        if (attempt.getPaymentPhase() != phase) return false;
        return switch (phase) {
            case AUTHORIZATION -> attempt.getState() == PaymentState.NOT_STARTED
                    || attempt.getState() == PaymentState.AUTHORIZATION_PENDING
                    || attempt.getState() == PaymentState.UNKNOWN;
            case CAPTURE -> attempt.getState() == PaymentState.AUTHORIZED
                    || attempt.getState() == PaymentState.CAPTURE_PENDING
                    || attempt.getState() == PaymentState.UNKNOWN;
            case VOID -> attempt.getState() == PaymentState.VOID_PENDING
                    || attempt.getState() == PaymentState.UNKNOWN;
        };
    }

    private PaymentAttempt finalizePhase(PhaseStart start, PaymentProviderResult result,
            PaymentProviderResult.Outcome outcome) {
        return transactions.execute(status -> {
            PaymentAttempt current = repository.findWithLockByAttemptId(start.attempt().getAttemptId())
                    .orElseThrow(() -> notFound("No payment attempt: " + start.attempt().getAttemptId()));
            if (terminal(current.getState()) || current.getPaymentPhase() != start.phase()
                    || !Objects.equals(start.requestId(), phaseRequestId(current, start.phase()))) {
                return current;
            }
            if (result != null) applyProviderResult(current, result);
            switch (start.phase()) {
                case AUTHORIZATION -> authorizationResult(current, outcome);
                case CAPTURE -> captureResult(current, outcome);
                case VOID -> voidResult(current, outcome);
            }
            repository.save(current);
            return current;
        });
    }

    private static String phaseRequestId(PaymentAttempt attempt, PaymentPhase phase) {
        return switch (phase) {
            case AUTHORIZATION -> attempt.getProviderOrderId() == null
                    ? attempt.getCreateOrderRequestId() : attempt.getAuthorizationRequestId();
            case CAPTURE -> attempt.getCaptureRequestId();
            case VOID -> attempt.getVoidRequestId();
        };
    }
    private PaymentResponse performPhase(PaymentAttempt attempt, String idempotencyKey) {
        return performPhase(attempt, idempotencyKey, true);
    }
    private PaymentResponse performPhase(PaymentAttempt attempt, String idempotencyKey, boolean continueCapture) {
        if (attempt.getPaymentPhase() == PaymentPhase.AUTHORIZATION && attempt.getProviderOrderId() == null) {
            ensureFxSnapshot(attempt.getAttemptId());
        }
        PhaseStart start = reservePhase(attempt.getAttemptId(), attempt.getPaymentPhase(), continueCapture);
        if (start.request() == null) return toResponse(start.attempt());
        boolean reconcileUnknownCapture = attempt.getPaymentPhase() == PaymentPhase.CAPTURE
                && attempt.getState() == PaymentState.UNKNOWN && psp.requiresFxSnapshot();
        PaymentProviderResult result;
        try {
            if (reconcileUnknownCapture) {
                result = psp.completeApproval(start.request(), start.request().providerOrderId());
                if (result.outcome() == PaymentProviderResult.Outcome.AUTHORIZED) {
                    result = psp.perform(start.request());
                }
            } else {
                result = psp.perform(start.request());
            }
        } catch (PaymentException exception) {
            if (exception.code() != PaymentException.Code.PAYMENT_PROVIDER_UNAVAILABLE) throw exception;
            return toResponse(finalizePhase(start, null, PaymentProviderResult.Outcome.UNKNOWN));
        }
        PaymentAttempt current = finalizePhase(start, result, result.outcome());
        if (continueCapture && current.getPaymentPhase() == PaymentPhase.CAPTURE
                && current.getState() == PaymentState.AUTHORIZED) {
            return performPhase(current, idempotencyKey, true);
        }
        return toResponse(current);
    }
    private void authorizationResult(PaymentAttempt attempt, PaymentProviderResult.Outcome result) {
        switch (result) {
            case AUTHORIZED -> {
                attempt.setState(PaymentState.AUTHORIZED);
                attempt.setPaymentPhase(PaymentPhase.CAPTURE);
                attempt.setAuthorizationActive(true);
            }
            case DECLINED -> {
                attempt.setState(PaymentState.DECLINED);
                attempt.setPaymentPhase(PaymentPhase.AUTHORIZATION);
                attempt.setAuthorizationActive(false);
            }
            case CAPTURED -> {
                attempt.setState(PaymentState.CAPTURED);
                attempt.setPaymentPhase(PaymentPhase.CAPTURE);
                attempt.setAuthorizationActive(false);
            }
            case VOIDED -> {
                attempt.setState(PaymentState.VOIDED);
                attempt.setPaymentPhase(PaymentPhase.VOID);
                attempt.setAuthorizationActive(false);
            }
            case PENDING -> {
                attempt.setState(PaymentState.AUTHORIZATION_PENDING);
                attempt.setPaymentPhase(PaymentPhase.AUTHORIZATION);
                attempt.setAuthorizationActive(true);
            }
            case UNKNOWN -> {
                attempt.setState(PaymentState.UNKNOWN);
                attempt.setPaymentPhase(PaymentPhase.AUTHORIZATION);
                attempt.setAuthorizationActive(true);
            }
            default -> throw invalidResult(PaymentPhase.AUTHORIZATION, result);
        }
    }

    private void captureResult(PaymentAttempt attempt, PaymentProviderResult.Outcome result) {
        switch (result) {
            case CAPTURED -> {
                attempt.setState(PaymentState.CAPTURED);
                attempt.setPaymentPhase(PaymentPhase.CAPTURE);
                attempt.setAuthorizationActive(false);
            }
            case DECLINED -> {
                attempt.setState(PaymentState.DECLINED);
                attempt.setPaymentPhase(PaymentPhase.CAPTURE);
                attempt.setAuthorizationActive(true);
            }
            case PENDING -> {
                attempt.setState(PaymentState.CAPTURE_PENDING);
                attempt.setPaymentPhase(PaymentPhase.CAPTURE);
                attempt.setAuthorizationActive(true);
            }
            case UNKNOWN -> {
                attempt.setState(PaymentState.UNKNOWN);
                attempt.setPaymentPhase(PaymentPhase.CAPTURE);
                attempt.setAuthorizationActive(true);
            }
            default -> throw invalidResult(PaymentPhase.CAPTURE, result);
        }
    }

    private void voidResult(PaymentAttempt attempt, PaymentProviderResult.Outcome result) {
        switch (result) {
            case VOIDED -> {
                attempt.setState(PaymentState.VOIDED);
                attempt.setPaymentPhase(PaymentPhase.VOID);
                attempt.setAuthorizationActive(false);
            }
            case PENDING -> {
                attempt.setState(PaymentState.VOID_PENDING);
                attempt.setPaymentPhase(PaymentPhase.VOID);
                attempt.setAuthorizationActive(true);
            }
            case UNKNOWN -> {
                attempt.setState(PaymentState.UNKNOWN);
                attempt.setPaymentPhase(PaymentPhase.VOID);
                attempt.setAuthorizationActive(true);
            }
            default -> throw invalidResult(PaymentPhase.VOID, result);
        }
    }

    private PaymentRequest providerRequest(PaymentAttempt attempt, PaymentPhase phase) {
        if (psp.requiresFxSnapshot() && !attempt.hasFxSnapshot()) {
            throw invalidRequest("A saved FX snapshot is required for this PayPal operation");
        }
        Long amount = psp.requiresFxSnapshot() ? attempt.getProviderAmountMinor() : attempt.getTotalWon();
        String providerCurrency = psp.requiresFxSnapshot() ? attempt.getProviderCurrency() : psp.providerCurrency();
        if (amount == null || amount <= 0 || providerCurrency == null || providerCurrency.isBlank()) {
            throw invalidRequest("저장된 결제 금액이 없어 결제를 진행할 수 없습니다");
        }
        String createId = stableRequestId(attempt, "create-order");
        String authorizeId = stableRequestId(attempt, "authorize");
        String captureId = stableRequestId(attempt, "capture");
        String voidId = stableRequestId(attempt, "void");
        attempt.setCreateOrderRequestId(createId);
        attempt.setAuthorizationRequestId(authorizeId);
        attempt.setCaptureRequestId(captureId);
        attempt.setVoidRequestId(voidId);
        UUID owner = attempt.getCheckoutId() == null ? attempt.getOrderId() : attempt.getCheckoutId();
        String ownerText = owner.toString();
        String base = publicBaseUrl;
        String returnPath = attempt.getCheckoutId() == null ? "/api/orders/" + ownerText + "/payment/return"
                : "/api/checkouts/" + ownerText + "/payment/return";
        String cancelPath = attempt.getCheckoutId() == null ? "/api/orders/" + ownerText + "/payment/cancel"
                : "/api/checkouts/" + ownerText + "/payment/cancel";
        PaymentOperation operation = switch (phase) {
            case AUTHORIZATION -> PaymentOperation.AUTHORIZATION;
            case CAPTURE -> PaymentOperation.CAPTURE;
            case VOID -> PaymentOperation.VOID;
        };
        String operationId = switch (phase) {
            case AUTHORIZATION -> attempt.getProviderOrderId() == null ? createId : authorizeId;
            case CAPTURE -> captureId;
            case VOID -> voidId;
        };
        return new PaymentRequest(operation, owner, attempt.getAttemptId(), amount, providerCurrency,
                attempt.getProviderOrderId(), attempt.getProviderAuthorizationId(),
                operationId, authorizeId, captureId, voidId, base + returnPath, base + cancelPath);
    }

    private static String stableRequestId(PaymentAttempt attempt, String operation) {
        return UUID.nameUUIDFromBytes((attempt.getAttemptId() + ":" + operation)
                .getBytes(StandardCharsets.UTF_8)).toString();
    }

    private static void applyProviderResult(PaymentAttempt attempt, PaymentProviderResult result) {
        if (result.orderId() != null) attempt.setProviderOrderId(result.orderId());
        if (result.authorizationId() != null) attempt.setProviderAuthorizationId(result.authorizationId());
        if (result.captureId() != null) attempt.setProviderCaptureId(result.captureId());
        if (result.approvalUrl() != null) attempt.setApprovalUrl(result.approvalUrl());
    }

    private static long amountWon(BigDecimal amount) {
        if (amount == null || amount.signum() <= 0) {
            throw invalidRequest("A positive payment amount is required");
        }
        try {
            return amount.setScale(0, RoundingMode.UNNECESSARY).longValueExact();
        } catch (ArithmeticException invalid) {
            throw invalidRequest("Payment amount must be a whole number of won");
        }
    }

    private <T> T withOwnerLock(UUID ownerId, Supplier<T> action) {
        return ownerLocks.withLock(ownerId, action);
    }

    private PaymentAttempt requireOrder(UUID orderId) {
        return repository.findByOrderId(orderId)
                .orElseThrow(() -> notFound("No payment attempt for order: " + orderId));
    }

    private PaymentAttempt requireCheckout(UUID checkoutId) {
        return repository.findByCheckoutId(checkoutId)
                .orElseThrow(() -> notFound("No payment attempt for checkout: " + checkoutId));
    }

    private static PaymentException notFound(String message) {
        return new PaymentException(PaymentException.Code.ATTEMPT_NOT_FOUND, message);
    }

    private static void validateOwnerAndKey(UUID ownerId, String idempotencyKey, String ownerName) {
        if (ownerId == null || idempotencyKey == null || idempotencyKey.isBlank()) {
            throw invalidRequest(ownerName + " and idempotencyKey required");
        }
    }

    private static PaymentException invalidRequest(String message) {
        return new PaymentException(PaymentException.Code.INVALID_REQUEST, message);
    }

    private static PaymentResponse toResponse(PaymentAttempt attempt) {
        return new PaymentResponse(attempt.getOrderId(), attempt.getCheckoutId(), attempt.getAttemptId(),
                attempt.getState(), attempt.isAuthorizationActive(), attempt.getProviderOrderId(),
                attempt.getProviderCaptureId(), attempt.getApprovalUrl());
    }

    private static boolean terminal(PaymentState state) {
        return state == PaymentState.CAPTURED || state == PaymentState.DECLINED || state == PaymentState.VOIDED;
    }

    private static IllegalStateException invalidResult(PaymentPhase phase, PaymentProviderResult.Outcome result) {
        return new IllegalStateException("Provider result " + result + " is invalid for " + phase);
    }
}
