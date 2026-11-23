package com.atlas.settlement.service.impl;

import com.atlas.settlement.repository.PayoutDispatchRepository;
import com.atlas.settlement.service.PayoutDispatchMetrics;
import com.atlas.settlement.service.PayoutDispatchPlan;
import com.atlas.settlement.service.PayoutDispatchRecord;
import com.atlas.settlement.service.PayoutDispatchStore;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.Objects;
import org.springframework.stereotype.Service;

@Service
public class PayoutDispatchStoreImpl implements PayoutDispatchStore {
    private final PayoutDispatchRepository repository;

    public PayoutDispatchStoreImpl(PayoutDispatchRepository repository) {
        this.repository = Objects.requireNonNull(repository, "repository");
    }

    @Override
    public Optional<PayoutDispatchRecord> find(UUID payoutId) {
        return repository.find(payoutId);
    }

    @Override
    public PayoutDispatchRecord enqueue(PayoutDispatchPlan p) {
        return repository.enqueue(p);
    }

    @Override
    public List<PayoutDispatchRecord> claim(int limit, Duration leaseDuration) {
        return repository.claim(limit, leaseDuration);
    }

    @Override
    public boolean reschedule(PayoutDispatchRecord r, Duration delay, String safeCode, String resultingStatus) {
        return repository.reschedule(r, delay, safeCode, resultingStatus);
    }

    @Override
    public boolean transition(PayoutDispatchRecord r, String status, String safeCode) {
        return repository.transition(r, status, safeCode);
    }

    @Override
    public boolean cancelPending(UUID payoutId) {
        return repository.cancelPending(payoutId);
    }

    @Override
    public boolean requeueNeedsReview(UUID payoutId) {
        return repository.requeueNeedsReview(payoutId);
    }

    @Override
    public boolean resolveNeedsReview(UUID payoutId, String result, String batchId, String itemId, Long feeMinor, Long feeWon, String safeCode) {
        return repository.resolveNeedsReview(payoutId, result, batchId, itemId, feeMinor, feeWon, safeCode);
    }

    @Override
    public boolean recordProviderEvidence(PayoutDispatchRecord r, String batchId, String batchStatus, String itemId, String itemStatus, Long feeMinor, String feeCurrency, Long feeWon) {
        return repository.recordProviderEvidence(r, batchId, batchStatus, itemId, itemStatus, feeMinor, feeCurrency, feeWon);
    }

    @Override
    public PayoutDispatchMetrics metricsSnapshot() {
        return repository.metricsSnapshot();
    }

    @Override
    public List<PayoutDispatchRecord> findNeedsReview(int limit, int offset) {
        return repository.findNeedsReview(limit, offset);
    }
}
