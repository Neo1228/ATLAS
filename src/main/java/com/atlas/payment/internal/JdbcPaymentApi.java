package com.atlas.payment.internal;

import com.atlas.payment.PaymentApi;
import com.atlas.payment.PaymentState;
import com.atlas.payment.PaymentView;

import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class JdbcPaymentApi implements PaymentApi {

    private record Attempt(UUID orderId, UUID attemptId, PaymentState state,
                           FakePsp.Operation operation, boolean authorizationActive) {
        PaymentView view() { return new PaymentView(orderId, attemptId, state, authorizationActive); }
    }

    private static final Object[] ORDER_LOCKS = createOrderLocks();

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final FakePsp psp;
    private final Supplier<UUID> ids;

    @Autowired
    public JdbcPaymentApi(JdbcTemplate jdbc, PlatformTransactionManager transactionManager, FakePsp psp) {
        this(jdbc, transactionManager, psp, UUID::randomUUID);
    }

    JdbcPaymentApi(JdbcTemplate jdbc, PlatformTransactionManager transactionManager, FakePsp psp,
                   Supplier<UUID> ids) {
        this.jdbc = Objects.requireNonNull(jdbc);
        this.transactions = new TransactionTemplate(Objects.requireNonNull(transactionManager));
        this.psp = Objects.requireNonNull(psp);
        this.ids = Objects.requireNonNull(ids);
    }

    @Override
    public PaymentView submitInitial(UUID orderId) {
        Objects.requireNonNull(orderId, "orderId");
        return withOrderLock(orderId, () -> {
            Attempt attempt = transactions.execute(status -> {
                jdbc.update("INSERT INTO payment_attempt (order_id, attempt_id, state, operation, authorization_active) "
                                + "VALUES (?, ?, 'NOT_STARTED', 'AUTHORIZATION', FALSE) ON CONFLICT (order_id) DO NOTHING",
                        orderId, ids.get());
                return require(orderId);
            });
            if (attempt.state() != PaymentState.NOT_STARTED) return attempt.view();
            return runOperation(attempt);
        });
    }

    @Override
    public PaymentView reconcile(UUID orderId) {
        Objects.requireNonNull(orderId, "orderId");
        return withOrderLock(orderId, () -> {
            Attempt attempt = transactions.execute(status -> require(orderId));
            if (terminal(attempt.state())) return attempt.view();
            return runOperation(attempt);
        });
    }

    @Override
    public PaymentView reconcileForCancellation(UUID orderId) {
        Objects.requireNonNull(orderId, "orderId");
        return withOrderLock(orderId, () -> {
            Attempt attempt = transactions.execute(status -> require(orderId));
            if (terminal(attempt.state())) return attempt.view();
            return runOperation(attempt, false);
        });
    }
    @Override
    public PaymentView voidAuthorization(UUID orderId) {
        Objects.requireNonNull(orderId, "orderId");
        return withOrderLock(orderId, () -> {
            Attempt attempt = transactions.execute(status -> require(orderId));
            if (!attempt.authorizationActive() || attempt.state() == PaymentState.VOIDED
                    || (attempt.state() != PaymentState.DECLINED && attempt.state() != PaymentState.AUTHORIZED)) {
                return attempt.view();
            }
            Attempt voidAttempt = new Attempt(attempt.orderId(), attempt.attemptId(), PaymentState.VOID_PENDING,
                    FakePsp.Operation.VOID, true);
            save(voidAttempt);
            return runOperation(voidAttempt);
        });
    }

    @Override
    public PaymentView find(UUID orderId) {
        Objects.requireNonNull(orderId, "orderId");
        List<Attempt> rows = jdbc.query("SELECT order_id, attempt_id, state, operation, authorization_active "
                        + "FROM payment_attempt WHERE order_id = ?",
                (rs, row) -> new Attempt(rs.getObject("order_id", UUID.class),
                        rs.getObject("attempt_id", UUID.class), PaymentState.valueOf(rs.getString("state")),
                        FakePsp.Operation.valueOf(rs.getString("operation")), rs.getBoolean("authorization_active")),
                orderId);
        return rows.isEmpty() ? null : rows.getFirst().view();
    }

    private PaymentView runOperation(Attempt attempt) {
        return runOperation(attempt, true);
    }
    private PaymentView runOperation(Attempt attempt, boolean continueCapture) {
        if (!continueCapture && attempt.operation() == FakePsp.Operation.CAPTURE
                && attempt.state() == PaymentState.AUTHORIZED) {
            return attempt.view();
        }
        String operationId = attempt.attemptId() + ":" + attempt.operation().name();
        FakePsp.Result result = psp.execute(attempt.operation(), operationId);
        Attempt updated = switch (attempt.operation()) {
            case AUTHORIZATION -> authorizationResult(attempt, result);
            case CAPTURE -> captureResult(attempt, result);
            case VOID -> voidResult(attempt, result);
        };
        save(updated);
        if (continueCapture && updated.operation() == FakePsp.Operation.CAPTURE
                && updated.state() == PaymentState.AUTHORIZED) {
            return runOperation(new Attempt(updated.orderId(), updated.attemptId(), PaymentState.CAPTURE_PENDING,
                    FakePsp.Operation.CAPTURE, true), true);
        }
        return updated.view();
    }

    private Attempt authorizationResult(Attempt a, FakePsp.Result result) {
        return switch (result) {
            case AUTHORIZED -> new Attempt(a.orderId(), a.attemptId(), PaymentState.AUTHORIZED,
                    FakePsp.Operation.CAPTURE, true);
            case DECLINED -> new Attempt(a.orderId(), a.attemptId(), PaymentState.DECLINED,
                    FakePsp.Operation.AUTHORIZATION, false);
            case PENDING -> new Attempt(a.orderId(), a.attemptId(), PaymentState.AUTHORIZATION_PENDING,
                    FakePsp.Operation.AUTHORIZATION, true);
            case UNKNOWN -> new Attempt(a.orderId(), a.attemptId(), PaymentState.UNKNOWN,
                    FakePsp.Operation.AUTHORIZATION, true);
            default -> throw invalidResult(a.operation(), result);
        };
    }

    private Attempt captureResult(Attempt a, FakePsp.Result result) {
        return switch (result) {
            case CAPTURED -> new Attempt(a.orderId(), a.attemptId(), PaymentState.CAPTURED,
                    FakePsp.Operation.CAPTURE, false);
            case DECLINED -> new Attempt(a.orderId(), a.attemptId(), PaymentState.DECLINED,
                    FakePsp.Operation.CAPTURE, true);
            case PENDING -> new Attempt(a.orderId(), a.attemptId(), PaymentState.CAPTURE_PENDING,
                    FakePsp.Operation.CAPTURE, true);
            case UNKNOWN -> new Attempt(a.orderId(), a.attemptId(), PaymentState.UNKNOWN,
                    FakePsp.Operation.CAPTURE, true);
            default -> throw invalidResult(a.operation(), result);
        };
    }

    private Attempt voidResult(Attempt a, FakePsp.Result result) {
        return switch (result) {
            case VOIDED -> new Attempt(a.orderId(), a.attemptId(), PaymentState.VOIDED,
                    FakePsp.Operation.VOID, false);
            case PENDING -> new Attempt(a.orderId(), a.attemptId(), PaymentState.VOID_PENDING,
                    FakePsp.Operation.VOID, true);
            case UNKNOWN -> new Attempt(a.orderId(), a.attemptId(), PaymentState.UNKNOWN,
                    FakePsp.Operation.VOID, true);
            default -> throw invalidResult(a.operation(), result);
        };
    }

    private void save(Attempt attempt) {
        transactions.executeWithoutResult(status -> jdbc.update(
                "UPDATE payment_attempt SET state = ?, operation = ?, authorization_active = ? WHERE order_id = ? AND attempt_id = ?",
                attempt.state().name(), attempt.operation().name(), attempt.authorizationActive(),
                attempt.orderId(), attempt.attemptId()));
    }

    private <T> T withOrderLock(UUID orderId, Supplier<T> operation) {
        synchronized (ORDER_LOCKS[Math.floorMod(orderId.hashCode(), ORDER_LOCKS.length)]) {
            return operation.get();
        }
    }

    private static Object[] createOrderLocks() {
        Object[] locks = new Object[256];
        for (int i = 0; i < locks.length; i++) locks[i] = new Object();
        return locks;
    }
    private Attempt require(UUID orderId) {
        List<Attempt> rows = jdbc.query("SELECT order_id, attempt_id, state, operation, authorization_active "
                        + "FROM payment_attempt WHERE order_id = ?",
                (rs, row) -> new Attempt(rs.getObject("order_id", UUID.class),
                        rs.getObject("attempt_id", UUID.class), PaymentState.valueOf(rs.getString("state")),
                        FakePsp.Operation.valueOf(rs.getString("operation")), rs.getBoolean("authorization_active")),
                orderId);
        if (rows.isEmpty()) throw new IllegalArgumentException("No payment attempt for order: " + orderId);
        return rows.getFirst();
    }

    private static boolean terminal(PaymentState state) {
        return state == PaymentState.CAPTURED || state == PaymentState.DECLINED || state == PaymentState.VOIDED;
    }

    private static IllegalStateException invalidResult(FakePsp.Operation operation, FakePsp.Result result) {
        return new IllegalStateException("Fake PSP result " + result + " is invalid for " + operation);
    }
}
