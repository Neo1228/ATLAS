package com.atlas.application.service.payout;

import com.atlas.settlement.model.PayoutDispatchRecord;
import com.atlas.settlement.model.PayoutDispatchState;
import com.atlas.settlement.service.PayoutDispatchStore;
import com.atlas.settlement.service.SettlementPayoutService;
import java.time.Duration;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PayoutDispatchOutcomeService {
    private final PayoutDispatchStore repository;
    private final SettlementPayoutService payouts;

    public PayoutDispatchOutcomeService(PayoutDispatchStore repository, SettlementPayoutService payouts) {
        this.repository = repository;
        this.payouts = payouts;
    }

    @Transactional
    public boolean complete(PayoutDispatchRecord r, String batchId, String batchStatus, String itemId,
                            String itemStatus, long feeMinor, long feeWon) {
        if (!repository.recordProviderEvidence(r, batchId, batchStatus, itemId, itemStatus, feeMinor, "USD", feeWon)) return false;
        if (!repository.transition(r, PayoutDispatchState.COMPLETED, null)) return false;
        payouts.confirmProviderSuccess(r.payoutId(), r.providerDebitBookValueWon(), r.payoutFxRoundingExpenseWon(), feeWon);
        return true;
    }

    @Transactional
    public boolean fail(PayoutDispatchRecord r, String batchId, String batchStatus, String itemId,
            String itemStatus, String safeCode, Long feeMinor, Long feeWon) {
        if (!repository.recordProviderEvidence(r, batchId, batchStatus, itemId, itemStatus,
                feeMinor, feeMinor == null ? null : "USD", feeWon)) return false;
        if (!repository.transition(r, PayoutDispatchState.FAILED, safeCode)) return false;
        payouts.failWithProviderEvidence(r.payoutId(), safeCode);
        return true;
    }

    @Transactional
    public boolean review(PayoutDispatchRecord r, String safeCode) {
        if (!repository.transition(r, PayoutDispatchState.NEEDS_REVIEW, safeCode)) return false;
        payouts.markProviderNeedsReview(r.payoutId(), safeCode);
        return true;
    }

    @Transactional
    public boolean cancel(PayoutDispatchRecord r) {
        return repository.transition(r, PayoutDispatchState.CANCELLED, "PAYOUT_CANCELLED");
    }

    @Transactional
    public boolean unclaimed(PayoutDispatchRecord r, String batchId, String batchStatus, String itemId,
            String itemStatus, Long feeMinor, Long feeWon, Duration delay) {
        if (!repository.recordProviderEvidence(r, batchId, batchStatus, itemId, itemStatus,
                feeMinor, feeMinor == null ? null : "USD", feeWon)) return false;
        if (!repository.reschedule(r, delay, null, PayoutDispatchState.WAITING_PROVIDER)) return false;
        payouts.markProviderUnclaimed(r.payoutId());
        return true;
    }

    @Transactional
    public boolean retry(PayoutDispatchRecord r, Duration delay, String code) {
        if (!repository.reschedule(r, delay, code,
                r.providerBatchId() == null ? PayoutDispatchState.PENDING : PayoutDispatchState.WAITING_PROVIDER)) return false;
        if (r.providerBatchId() == null && ("PROVIDER_UNKNOWN".equals(code) || "DISPATCH_ERROR".equals(code))) {
            payouts.markProviderUnknown(r.payoutId());
        }
        return true;
    }

    @Transactional
    public boolean accepted(PayoutDispatchRecord r, String batchId, String batchStatus, String itemId,
            String itemStatus, Long feeMinor, Long feeWon, Duration delay) {
        if (!repository.recordProviderEvidence(r, batchId, batchStatus, itemId, itemStatus,
                feeMinor, feeMinor == null ? null : "USD", feeWon)) return false;
        if (!repository.reschedule(r, delay, null, PayoutDispatchState.WAITING_PROVIDER)) return false;
        payouts.markProviderAccepted(r.payoutId());
        return true;
    }

    @Transactional
    public boolean reviewWithEvidence(PayoutDispatchRecord r, String batchId, String batchStatus,
                                      String itemId, String itemStatus, String code) {
        if (!repository.recordProviderEvidence(r, batchId, batchStatus, itemId, itemStatus, null, null, null)) return false;
        if (!repository.transition(r, PayoutDispatchState.NEEDS_REVIEW, code)) return false;
        payouts.markProviderNeedsReview(r.payoutId(), code);
        return true;
    }
}
