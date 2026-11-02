package com.atlas.payment.internal;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Queue;
import org.springframework.stereotype.Component;

/** Local deterministic provider simulator; intentionally does not reproduce PayPal behavior. */
@Component
public final class FakePsp {

    public enum Operation { AUTHORIZATION, CAPTURE, VOID }
    public enum Result { AUTHORIZED, CAPTURED, DECLINED, PENDING, UNKNOWN, VOIDED }

    private record Key(Operation operation, String operationId) { }
    private static final class ProviderOperation {
        private final Queue<Result> transitions;
        private Result current;
        private ProviderOperation(Queue<Result> transitions) { this.transitions = transitions; }
    }

    private final Map<Key, ProviderOperation> operations = new HashMap<>();
    private final Map<Operation, Queue<Result>> plannedTransitions = new HashMap<>();

    public FakePsp() {
        for (Operation operation : Operation.values()) plannedTransitions.put(operation, new ArrayDeque<>());
    }

    /** Queue the next operation's progression; each provider call advances one entry. */
    public synchronized void enqueue(Operation operation, Result... progression) {
        Objects.requireNonNull(operation, "operation");
        if (progression == null || progression.length == 0) throw new IllegalArgumentException("A provider progression is required");
        Queue<Result> values = new ArrayDeque<>();
        for (Result result : progression) values.add(Objects.requireNonNull(result, "result"));
        plannedTransitions.get(operation).addAll(values);
    }

    synchronized Result execute(Operation operation, String operationId) {
        Key key = new Key(operation, Objects.requireNonNull(operationId, "operationId"));
        ProviderOperation providerOperation = operations.get(key);
        if (providerOperation == null) {
            Queue<Result> progression = plannedTransitions.get(operation);
            if (progression.isEmpty()) progression.add(defaultResult(operation));
            Queue<Result> copy = new ArrayDeque<>(progression);
            progression.clear();
            providerOperation = new ProviderOperation(copy);
            operations.put(key, providerOperation);
        }
        if (!providerOperation.transitions.isEmpty()) providerOperation.current = providerOperation.transitions.remove();
        return providerOperation.current;
    }

    private static Result defaultResult(Operation operation) {
        return switch (operation) {
            case AUTHORIZATION -> Result.AUTHORIZED;
            case CAPTURE -> Result.CAPTURED;
            case VOID -> Result.VOIDED;
        };
    }

}
