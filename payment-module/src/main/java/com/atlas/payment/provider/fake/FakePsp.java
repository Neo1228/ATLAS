package com.atlas.payment.provider.fake;

import com.atlas.payment.persistence.state.PaymentPhase;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Queue;
import org.springframework.stereotype.Component;

/** 각 결제 단계 결과를 순서대로 반환하는 로컬 PSP 시뮬레이터. 실제 PSP 상태는 재현하지 않음. */
@Component
public final class FakePsp {

    public enum Result { AUTHORIZED, CAPTURED, DECLINED, PENDING, UNKNOWN, VOIDED }

    private record RequestKey(PaymentPhase phase, String requestId) { }

    private static final class ProviderResults {
        private final Queue<Result> pending;
        private Result current;

        private ProviderResults(Queue<Result> pending) {
            this.pending = pending;
        }
    }

    private final Map<RequestKey, ProviderResults> resultsByRequest = new HashMap<>();
    private final Map<PaymentPhase, Queue<Result>> plannedResults = new HashMap<>();

    public FakePsp() {
        for (PaymentPhase phase : PaymentPhase.values()) {
            plannedResults.put(phase, new ArrayDeque<>());
        }
    }

    /** 다음 결제 단계의 결과 순서를 등록. 호출마다 다음 결과를 하나씩 소비. */
    public synchronized void enqueue(PaymentPhase phase, Result... progression) {
        Objects.requireNonNull(phase, "phase");
        if (progression == null || progression.length == 0) {
            throw new IllegalArgumentException("A provider progression is required");
        }
        Queue<Result> values = new ArrayDeque<>();
        for (Result result : progression) values.add(Objects.requireNonNull(result, "result"));
        plannedResults.get(phase).addAll(values);
    }

    public synchronized Result perform(PaymentPhase phase, String requestId) {
        RequestKey key = new RequestKey(phase, Objects.requireNonNull(requestId, "requestId"));
        ProviderResults results = resultsByRequest.get(key);
        if (results == null) {
            Queue<Result> progression = plannedResults.get(phase);
            if (progression.isEmpty()) progression.add(defaultResult(phase));
            Queue<Result> copy = new ArrayDeque<>(progression);
            progression.clear();
            results = new ProviderResults(copy);
            resultsByRequest.put(key, results);
        }
        if (!results.pending.isEmpty()) results.current = results.pending.remove();
        return results.current;
    }

    private static Result defaultResult(PaymentPhase phase) {
        return switch (phase) {
            case AUTHORIZATION -> Result.AUTHORIZED;
            case CAPTURE -> Result.CAPTURED;
            case VOID -> Result.VOIDED;
        };
    }
}
