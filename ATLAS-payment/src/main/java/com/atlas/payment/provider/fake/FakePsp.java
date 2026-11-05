package com.atlas.payment.provider.fake;

import com.atlas.payment.persistence.state.FakePspResult;
import com.atlas.payment.persistence.state.PaymentPhase;
import com.atlas.payment.repository.FakePspRepository;
import java.util.Arrays;
import java.util.Objects;
import org.springframework.stereotype.Component;

/** 진행 결과를 PostgreSQL에 저장하는 local-only PSP simulator. 실제 PSP 호출 없음. */
@Component
public class FakePsp {

    private final FakePspRepository repository;

    public FakePsp(FakePspRepository repository) {
        this.repository = Objects.requireNonNull(repository);
    }

    public void enqueue(PaymentPhase phase, FakePspResult... progression) {
        Objects.requireNonNull(phase, "phase");
        if (progression.length == 0) throw new IllegalArgumentException("A progression is required");
        repository.enqueue(phase, Arrays.stream(progression).map(result -> Objects.requireNonNull(result, "result")).toList());
    }

    public FakePspResult perform(PaymentPhase phase, String requestId, String idempotencyKey) {
        Objects.requireNonNull(phase, "phase");
        if (requestId == null || requestId.isBlank()) throw new IllegalArgumentException("requestId is required");
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new IllegalArgumentException("idempotencyKey is required");
        }
        return repository.perform(phase, requestId, idempotencyKey, defaultResult(phase));
    }

    private static FakePspResult defaultResult(PaymentPhase phase) {
        return switch (phase) {
            case AUTHORIZATION -> FakePspResult.AUTHORIZED;
            case CAPTURE -> FakePspResult.CAPTURED;
            case VOID -> FakePspResult.VOIDED;
        };
    }
}
