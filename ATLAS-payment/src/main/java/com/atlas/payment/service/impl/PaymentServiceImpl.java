package com.atlas.payment.service.impl;

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

    private static final Object[] ORDER_LOCKS = createOrderLocks();

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
        if (orderId == null) throw invalidRequest();
        return withOrderLock(orderId, () -> {
            PaymentAttempt attempt = transactions.execute(status -> {
                repository.insertInitial(orderId, ids.get());
                return require(orderId);
            });
            if (attempt.getState() != PaymentState.NOT_STARTED) return toResponse(attempt);
            return performPhase(attempt);
        });
    }

    @Override
    public PaymentResponse reconcile(UUID orderId) {
        if (orderId == null) throw invalidRequest();
        return withOrderLock(orderId, () -> {
            PaymentAttempt attempt = transactions.execute(status -> require(orderId));
            if (terminal(attempt.getState())) return toResponse(attempt);
            return performPhase(attempt);
        });
    }

    @Override
    public PaymentResponse reconcileForCancellation(UUID orderId) {
        if (orderId == null) throw invalidRequest();
        return withOrderLock(orderId, () -> {
            PaymentAttempt attempt = transactions.execute(status -> require(orderId));
            if (terminal(attempt.getState())) return toResponse(attempt);
            return performPhase(attempt, false);
        });
    }

    @Override
    public PaymentResponse voidAuthorization(UUID orderId) {
        if (orderId == null) throw invalidRequest();
        return withOrderLock(orderId, () -> {
            PaymentAttempt attempt = transactions.execute(status -> require(orderId));
            if (!attempt.isAuthorizationActive() || attempt.getState() == PaymentState.VOIDED
                    || (attempt.getState() != PaymentState.DECLINED
                    && attempt.getState() != PaymentState.AUTHORIZED)) {
                return toResponse(attempt);
            }
            attempt.setState(PaymentState.VOID_PENDING);
            attempt.setPaymentPhase(PaymentPhase.VOID);
            attempt.setAuthorizationActive(true);
            save(attempt);
            return performPhase(attempt);
        });
    }

    @Override
    public PaymentResponse find(UUID orderId) {
        if (orderId == null) throw invalidRequest();
        PaymentAttempt attempt = repository.findById(orderId).orElse(null);
        return attempt == null ? null : toResponse(attempt);
    }

    private PaymentResponse performPhase(PaymentAttempt attempt) {
        return performPhase(attempt, true);
    }

    private PaymentResponse performPhase(PaymentAttempt attempt, boolean continueCapture) {
        PaymentPhase phase = attempt.getPaymentPhase();
        if (!continueCapture && phase == PaymentPhase.CAPTURE
                && attempt.getState() == PaymentState.AUTHORIZED) {
            return toResponse(attempt);
        }
        String phaseId = attempt.getAttemptId() + ":" + phase.name();
        FakePsp.Result result = psp.perform(phase, phaseId);
        switch (phase) {
            case AUTHORIZATION -> authorizationResult(attempt, result);
            case CAPTURE -> captureResult(attempt, result);
            case VOID -> voidResult(attempt, result);
        }
        save(attempt);
        if (continueCapture && attempt.getPaymentPhase() == PaymentPhase.CAPTURE
                && attempt.getState() == PaymentState.AUTHORIZED) {
            attempt.setState(PaymentState.CAPTURE_PENDING);
            return performPhase(attempt, true);
        }
        return toResponse(attempt);
    }

    private void authorizationResult(PaymentAttempt attempt, FakePsp.Result result) {
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

    private void captureResult(PaymentAttempt attempt, FakePsp.Result result) {
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

    private void voidResult(PaymentAttempt attempt, FakePsp.Result result) {
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

    private <T> T withOrderLock(UUID orderId, Supplier<T> phase) {
        synchronized (ORDER_LOCKS[Math.floorMod(orderId.hashCode(), ORDER_LOCKS.length)]) {
            return phase.get();
        }
    }

    private static Object[] createOrderLocks() {
        Object[] locks = new Object[256];
        for (int i = 0; i < locks.length; i++) locks[i] = new Object();
        return locks;
    }

    private PaymentAttempt require(UUID orderId) {
        return repository.findById(orderId)
                .orElseThrow(() -> new PaymentException(PaymentException.Code.ATTEMPT_NOT_FOUND,
                        "No payment attempt for order: " + orderId));
    }
    private static PaymentException invalidRequest() {
        return new PaymentException(PaymentException.Code.INVALID_REQUEST, "orderId is required");
    }

    private static PaymentResponse toResponse(PaymentAttempt attempt) {
        return new PaymentResponse(attempt.getOrderId(), attempt.getAttemptId(), attempt.getState(),
                attempt.isAuthorizationActive());
    }

    private static boolean terminal(PaymentState state) {
        return state == PaymentState.CAPTURED || state == PaymentState.DECLINED || state == PaymentState.VOIDED;
    }

    private static IllegalStateException invalidResult(PaymentPhase phase, FakePsp.Result result) {
        return new IllegalStateException("Fake PSP result " + result + " is invalid for " + phase);
    }
}
