package com.atlas.application.service.payout;

import com.atlas.application.security.PayoutRecipientCipher;
import com.atlas.settlement.dto.response.PayoutResponse;
import com.atlas.settlement.exception.SettlementException;
import com.atlas.settlement.model.PayoutDispatchPlan;
import com.atlas.settlement.model.PayoutDispatchRecord;
import com.atlas.settlement.model.PayoutDispatchState;
import com.atlas.settlement.model.PayoutOperatorActionType;
import com.atlas.settlement.model.SettlementPayoutState;
import com.atlas.settlement.service.PayoutDispatchStore;
import com.atlas.settlement.service.PayoutOperatorActionStore;
import com.atlas.settlement.service.SettlementPayoutService;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.util.Objects;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Commits the operator decision, Seller payout state and durable provider intent atomically. */
@Service
@ConditionalOnProperty(prefix = "atlas.payment.paypal.payouts", name = "enabled", havingValue = "true")
public class PayoutDispatchApprovalCommitService {
    private final SettlementPayoutService payouts;
    private final SellerPayoutProfileService profiles;
    private final PayoutRecipientCipher cipher;
    private final PayoutDispatchStore dispatches;
    private final PayoutOperatorActionStore actions;
    private final Clock clock;

    public PayoutDispatchApprovalCommitService(SettlementPayoutService payouts,
            SellerPayoutProfileService profiles, PayoutRecipientCipher cipher,
            PayoutDispatchStore dispatches, PayoutOperatorActionStore actions, Clock clock) {
        this.payouts = Objects.requireNonNull(payouts);
        this.profiles = Objects.requireNonNull(profiles);
        this.cipher = Objects.requireNonNull(cipher);
        this.dispatches = Objects.requireNonNull(dispatches);
        this.actions = Objects.requireNonNull(actions);
        this.clock = Objects.requireNonNull(clock);
    }

    @Transactional
    public PayoutResponse.SellerPayout approve(UUID payoutId, PayoutDispatchPlan plan,
            SellerPayoutProfileService.ActiveRecipient expectedRecipient,
            String idempotencyKey, String actor, String reason) {
        if (!payoutId.equals(plan.payoutId())) throw invalid("Payout dispatch plan does not match payout");
        PayoutResponse.SellerPayout current = payouts.find(payoutId);
        if (current.state() != SettlementPayoutState.REQUESTED
                && current.state() != SettlementPayoutState.APPROVED) {
            throw conflict("Only a requested payout can be approved for provider dispatch");
        }
        if (current.amountWon() != plan.requestedAmountWon()
                || !current.sellerId().equals(expectedRecipient.sellerId())) {
            throw conflict("Payout changed while its provider dispatch plan was prepared");
        }
        SellerPayoutProfileService.ActiveRecipient currentRecipient =
                profiles.requireActiveRecipient(current.sellerId());
        if (!sameRecipient(expectedRecipient, currentRecipient)) {
            throw conflict("Seller payout profile changed while approval was prepared");
        }
        PayoutResponse.SellerPayout approved = current.state() == SettlementPayoutState.APPROVED
                ? current : payouts.approve(payoutId);
        if (approved.state() != SettlementPayoutState.APPROVED) {
            throw conflict("Payout approval did not reach the approved state");
        }
        // payouts.approve 가 Seller 지급 잠금을 잡은 뒤에 읽으므로, 다른 운영자의 승인이 먼저 커밋됐다면 outbox 가 이미 보인다.
        // 그 경우 이 요청은 승인을 만든 것이 아니라 재전송이라 PAYOUT_APPROVED 행을 또 남기지 않는다(EXP-096 H4).
        boolean firstApproval = dispatches.find(payoutId).isEmpty();
        PayoutDispatchRecord record = dispatches.enqueue(plan);
        if (!sameDispatchFacts(plan, record)) {
            throw conflict("Payout already has different immutable dispatch facts");
        }
        String storedRecipient = cipher.decrypt(record.recipientEmailCiphertext());
        if (!MessageDigest.isEqual(storedRecipient.getBytes(StandardCharsets.UTF_8),
                currentRecipient.verifiedEmail().getBytes(StandardCharsets.UTF_8))) {
            throw conflict("Existing provider dispatch recipient differs from the verified Seller identity");
        }
        recordAction(payoutId, idempotencyKey, firstApproval
                ? PayoutOperatorActionType.PAYOUT_APPROVED : PayoutOperatorActionType.PAYOUT_APPROVAL_REPLAYED,
                actor, reason);
        return approved;
    }

    @Transactional
    public PayoutResponse.SellerPayout replay(UUID payoutId, String idempotencyKey, String actor, String reason) {
        PayoutResponse.SellerPayout payout = payouts.find(payoutId);
        if (actions.matches(payoutId, idempotencyKey, PayoutOperatorActionType.PAYOUT_APPROVED, actor, reason)) return payout;
        if (payout.state() != SettlementPayoutState.APPROVED) {
            throw conflict("Payout approval is already terminal or in provider reconciliation");
        }
        recordAction(payoutId, idempotencyKey, PayoutOperatorActionType.PAYOUT_APPROVAL_REPLAYED, actor, reason);
        return payout;
    }

    @Transactional
    public PayoutResponse.SellerPayout cancel(UUID payoutId, String idempotencyKey, String actor, String reason) {
        PayoutResponse.SellerPayout cancelled = payouts.cancelBeforeDispatch(payoutId, "OPERATOR_CANCELLED");
        dispatches.cancelPending(payoutId);
        recordAction(payoutId, idempotencyKey, PayoutOperatorActionType.PAYOUT_CANCELLED, actor, reason);
        return cancelled;
    }

    @Transactional
    public PayoutResponse.SellerPayout requeueNeedsReview(UUID payoutId, String idempotencyKey,
            String actor, String reason) {
        PayoutResponse.SellerPayout payout = payouts.find(payoutId);
        PayoutDispatchRecord record = dispatches.find(payoutId)
                .orElseThrow(() -> conflict("Payout has no provider dispatch record"));
        if (actions.matches(payoutId, idempotencyKey, PayoutOperatorActionType.PAYOUT_REQUEUED, actor, reason)) return payout;
        if (record.status() == PayoutDispatchState.NEEDS_REVIEW) {
            if (payout.state() != SettlementPayoutState.NEEDS_REVIEW) {
                throw conflict("Payout state does not permit provider requeue");
            }
            if (dispatches.requeueNeedsReview(payoutId)) {
                recordAction(payoutId, idempotencyKey, PayoutOperatorActionType.PAYOUT_REQUEUED, actor, reason);
                return payout;
            }
            PayoutDispatchRecord latest = dispatches.find(payoutId).orElseThrow(
                    () -> conflict("Payout dispatch record disappeared during requeue"));
            if ((latest.status() == PayoutDispatchState.PENDING || latest.status() == PayoutDispatchState.WAITING_PROVIDER)
                    && actions.matches(payoutId, idempotencyKey, PayoutOperatorActionType.PAYOUT_REQUEUED, actor, reason)) {
                return payout;
            }
            throw conflict("Payout is no longer eligible for provider requeue");
        }
        if ((record.status() == PayoutDispatchState.PENDING || record.status() == PayoutDispatchState.WAITING_PROVIDER)
                && actions.matches(payoutId, idempotencyKey, PayoutOperatorActionType.PAYOUT_REQUEUED, actor, reason)) {
            return payout;
        }
        throw conflict("Payout is not waiting for manual provider requeue");
    }

    private static boolean sameDispatchFacts(PayoutDispatchPlan plan, PayoutDispatchRecord record) {
        return plan.payoutId().equals(record.payoutId()) && plan.profileVersion() == record.profileVersion()
                && plan.recipientCountryCode().equals(record.recipientCountryCode())
                && plan.requestedAmountWon() == record.requestedAmountWon()
                && plan.providerAmountMinor() == record.providerAmountMinor()
                && plan.fxRateWonPerUsd().compareTo(record.fxRateWonPerUsd()) == 0
                && plan.fxSource().equals(record.fxSource()) && plan.fxAsOfDate().equals(record.fxAsOfDate())
                && plan.providerDebitBookValueWon() == record.providerDebitBookValueWon()
                && plan.payoutFxRoundingExpenseWon() == record.payoutFxRoundingExpenseWon()
                && plan.senderBatchId().equals(record.senderBatchId())
                && plan.senderItemId().equals(record.senderItemId())
                && plan.paypalRequestId().equals(record.paypalRequestId())
                && plan.requestPayloadSha256().equals(record.requestPayloadSha256());
    }
    private void recordAction(UUID payoutId, String idempotencyKey, PayoutOperatorActionType action,
            String actor, String reason) {
        if (!actions.record(payoutId, idempotencyKey, action, actor, reason, clock.instant())) {
            throw conflict("Payout operator idempotency key was reused for different facts");
        }
    }

    private static boolean sameRecipient(SellerPayoutProfileService.ActiveRecipient left,
            SellerPayoutProfileService.ActiveRecipient right) {
        return left.sellerId().equals(right.sellerId()) && left.accountId().equals(right.accountId())
                && left.profileVersion() == right.profileVersion()
                && left.countryCode().equals(right.countryCode()) && left.currency().equals(right.currency())
                && MessageDigest.isEqual(left.verifiedEmail().getBytes(StandardCharsets.UTF_8),
                        right.verifiedEmail().getBytes(StandardCharsets.UTF_8));
    }

    private static SettlementException invalid(String message) {
        return new SettlementException(SettlementException.Code.INVALID_REQUEST, message);
    }

    private static SettlementException conflict(String message) {
        return new SettlementException(SettlementException.Code.CONFLICT, message);
    }
}
