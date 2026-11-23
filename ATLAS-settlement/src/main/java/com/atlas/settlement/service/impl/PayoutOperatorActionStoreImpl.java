package com.atlas.settlement.service.impl;

import com.atlas.settlement.repository.PayoutOperatorActionRepository;
import com.atlas.settlement.service.PayoutOperatorActionStore;
import java.time.Instant;
import java.util.UUID;
import java.util.Objects;
import org.springframework.stereotype.Service;

@Service
public class PayoutOperatorActionStoreImpl implements PayoutOperatorActionStore {
    private final PayoutOperatorActionRepository repository;

    public PayoutOperatorActionStoreImpl(PayoutOperatorActionRepository repository) {
        this.repository = Objects.requireNonNull(repository, "repository");
    }

    @Override
    public boolean record(UUID payoutId, String idempotencyKey, String action, String actor, String reason, Instant now) {
        return repository.record(payoutId, idempotencyKey, action, actor, reason, now);
    }

    @Override
    public boolean matches(UUID payoutId, String idempotencyKey, String action, String actor, String reason) {
        return repository.matches(payoutId, idempotencyKey, action, actor, reason);
    }
}
