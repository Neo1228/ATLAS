package com.atlas.payment.service.impl;

import com.atlas.payment.persistence.state.FakePspResult;
import com.atlas.payment.persistence.state.PaymentPhase;
import com.atlas.payment.service.PaymentService;
import com.atlas.payment.persistence.state.PaymentState;
import com.atlas.payment.exception.PaymentException;
import com.atlas.payment.dto.response.PaymentResponse;
import com.atlas.payment.persistence.entity.PaymentAttempt;
import com.atlas.payment.provider.fake.FakePsp;
import com.atlas.payment.repository.PaymentAttemptRepository;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class PaymentServiceImpl implements PaymentService {

    private static final Object[] OWNER_LOCKS = createOwnerLocks();

    private final PaymentAttemptRepository repository;
    private final TransactionTemplate transactions;
    private final FakePsp psp;
    private final Supplier<UUID> ids;

    @Autowired
    public PaymentServiceImpl(PaymentAttemptRepository repository,
                              PlatformTransactionManager transactionManager, FakePsp psp) {
        this(repository, transactionManager, psp, UUID::randomUUID);
    }

    PaymentServiceImpl(PaymentAttemptRepository repository, PlatformTransactionManager transactionManager,
                       FakePsp psp, Supplier<UUID> ids) {
        this.repository = Objects.requireNonNull(repository);
        this.transactions = new TransactionTemplate(Objects.requireNonNull(transactionManager));
        this.psp = Objects.requireNonNull(psp);
        this.ids = Objects.requireNonNull(ids);
    }

    @Override
    public PaymentResponse submitInitial(UUID orderId) {
        if (orderId == null) throw invalidRequest("orderId required");
        return withOwnerLock(orderId, () -> {
            PaymentAttempt attempt = transactions.execute(status -> {
                repository.insertInitial(orderId, ids.get());
                return requireOrder(orderId);
            });
            if (attempt.getState() != PaymentState.NOT_STARTED) return toResponse(attempt);
            return performPhase(attempt, "initial:" + attempt.getAttemptId());
        });
    }

    @Override
    public PaymentResponse submitCheckout(UUID checkoutId) {
        if (checkoutId == null) throw invalidRequest("checkoutId required");
        return withOwnerLock(checkoutId, () -> {
            PaymentAttempt attempt = transactions.execute(status -> {
                repository.insertCheckoutInitial(checkoutId, ids.get());
                return requireCheckout(checkoutId);
            });
            if (attempt.getState() != PaymentState.NOT_STARTED) return toResponse(attempt);
            return performPhase(attempt, "initial:" + attempt.getAttemptId());
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
        return withOwnerLock(orderId, () -> {
            PaymentAttempt attempt = transactions.execute(status -> requireOrder(orderId));
            if (!attempt.isAuthorizationActive() || attempt.getState() == PaymentState.VOIDED
                    || (attempt.getState() != PaymentState.DECLINED
                    && attempt.getState() != PaymentState.AUTHORIZED)) {
                return toResponse(attempt);
            }
            attempt.setState(PaymentState.VOID_PENDING);
            attempt.setPaymentPhase(PaymentPhase.VOID);
            attempt.setAuthorizationActive(true);
            save(attempt);
            return performPhase(attempt, idempotencyKey);
        });
    }

    @Override

    public PaymentResponse voidCheckoutAuthorization(UUID checkoutId, String idempotencyKey) {
        validateOwnerAndKey(checkoutId, idempotencyKey, "checkoutId");
        return withOwnerLock(checkoutId, () -> {
            PaymentAttempt attempt = transactions.execute(status -> requireCheckout(checkoutId));
            if (!attempt.isAuthorizationActive() || attempt.getState() == PaymentState.VOIDED
                    || (attempt.getState() != PaymentState.DECLINED
                    && attempt.getState() != PaymentState.AUTHORIZED)) {
                return toResponse(attempt);
            }
            attempt.setState(PaymentState.VOID_PENDING);
            attempt.setPaymentPhase(PaymentPhase.VOID);
            attempt.setAuthorizationActive(true);
            save(attempt);
            return performPhase(attempt, idempotencyKey);
        });
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

    private PaymentResponse performPhase(PaymentAttempt attempt, String idempotencyKey) {
        return performPhase(attempt, idempotencyKey, true);
    }

    private PaymentResponse performPhase(PaymentAttempt attempt, String idempotencyKey, boolean continueCapture) {
        PaymentPhase phase = attempt.getPaymentPhase();
        if (!continueCapture && phase == PaymentPhase.CAPTURE
                && attempt.getState() == PaymentState.AUTHORIZED) {
            return toResponse(attempt);
        }
        String phaseId = attempt.getAttemptId() + ":" + phase.name();
        FakePspResult result = psp.perform(phase, phaseId, idempotencyKey);
        switch (phase) {
            case AUTHORIZATION -> authorizationResult(attempt, result);
            case CAPTURE -> captureResult(attempt, result);
            case VOID -> voidResult(attempt, result);
        }
        save(attempt);
        if (continueCapture && attempt.getPaymentPhase() == PaymentPhase.CAPTURE
                && attempt.getState() == PaymentState.AUTHORIZED) {
            attempt.setState(PaymentState.CAPTURE_PENDING);
            return performPhase(attempt, idempotencyKey, true);
        }
        return toResponse(attempt);
    }

    private void authorizationResult(PaymentAttempt attempt, FakePspResult result) {
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

    private void captureResult(PaymentAttempt attempt, FakePspResult result) {
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

    private void voidResult(PaymentAttempt attempt, FakePspResult result) {
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

    private void save(PaymentAttempt attempt) {
        transactions.executeWithoutResult(status -> repository.save(attempt));
    }

    private <T> T withOwnerLock(UUID ownerId, Supplier<T> action) {
        synchronized (OWNER_LOCKS[Math.floorMod(ownerId.hashCode(), OWNER_LOCKS.length)]) {
            return action.get();
        }
    }

    private static Object[] createOwnerLocks() {
        Object[] locks = new Object[256];
        for (int i = 0; i < locks.length; i++) locks[i] = new Object();
        return locks;
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
                attempt.getState(), attempt.isAuthorizationActive());
    }

    private static boolean terminal(PaymentState state) {
        return state == PaymentState.CAPTURED || state == PaymentState.DECLINED || state == PaymentState.VOIDED;
    }

    private static IllegalStateException invalidResult(PaymentPhase phase, FakePspResult result) {
        return new IllegalStateException("Fake PSP result " + result + " is invalid for " + phase);
    }
}
