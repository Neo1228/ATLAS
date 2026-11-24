package com.atlas.application.service.payout;

import com.atlas.application.dto.request.SettlementHttpRequest;
import com.atlas.settlement.dto.response.PayoutResponse;
import com.atlas.settlement.exception.SettlementException;
import com.atlas.settlement.model.PayoutDispatchRecord;
import com.atlas.settlement.model.PayoutDispatchState;
import com.atlas.settlement.model.PayoutOperatorActionType;
import com.atlas.settlement.model.SettlementPayoutState;
import com.atlas.settlement.service.PayoutDispatchStore;
import com.atlas.settlement.service.PayoutOperatorActionStore;
import com.atlas.settlement.service.SettlementPayoutService;
import java.time.Clock;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/** Explicitly audited manual path for ineligible profiles and legacy payouts; never dispatches PayPal. */
@Service
public class PayoutManualOperatorService {
    private final SettlementPayoutService payouts;
    private final PayoutFxConversionService conversions;
    private final PayoutDispatchStore dispatches;
    private final PayoutOperatorActionStore actions;
    private final Clock clock;

    public PayoutManualOperatorService(SettlementPayoutService payouts, PayoutDispatchStore dispatches,
            PayoutOperatorActionStore actions, PayoutFxConversionService conversions, Clock clock) {
        this.payouts = Objects.requireNonNull(payouts);
        this.dispatches = Objects.requireNonNull(dispatches);
        this.actions = Objects.requireNonNull(actions);
        this.conversions = Objects.requireNonNull(conversions);
        this.clock = Objects.requireNonNull(clock);
    }

    @Transactional
    public PayoutResponse.SellerPayout approve(UUID payoutId, String idempotencyKey, String actor, String reason) {
        validate(payoutId, idempotencyKey, actor, reason);
        requireNoProviderOutbox(payoutId);
        if (actions.matches(payoutId, idempotencyKey, PayoutOperatorActionType.MANUAL_PAYOUT_APPROVED, actor, reason.trim())) {
            return payouts.find(payoutId);
        }
        PayoutResponse.SellerPayout approved = payouts.approve(payoutId);
        requireNoProviderOutbox(payoutId);
        recordAction(payoutId, idempotencyKey, PayoutOperatorActionType.MANUAL_PAYOUT_APPROVED, actor, reason.trim());
        return approved;
    }

    @Transactional
    public PayoutResponse.SellerPayout markSent(UUID payoutId, String idempotencyKey, String actor, String evidenceReference) {
        validate(payoutId, idempotencyKey, actor, evidenceReference);
        requireNoProviderOutbox(payoutId);
        if (actions.matches(payoutId, idempotencyKey, PayoutOperatorActionType.MANUAL_PAYOUT_SENT, actor,
                evidenceReference.trim())) {
            return payouts.find(payoutId);
        }
        PayoutResponse.SellerPayout sent = payouts.markSent(payoutId);
        requireNoProviderOutbox(payoutId);
        recordAction(payoutId, idempotencyKey, PayoutOperatorActionType.MANUAL_PAYOUT_SENT, actor, evidenceReference.trim());
        return sent;
    }

    @Transactional
    public PayoutResponse.SellerPayout confirm(UUID payoutId, String idempotencyKey, String actor, String evidenceReference) {
        validate(payoutId, idempotencyKey, actor, evidenceReference);
        requireNoProviderOutbox(payoutId);
        if (actions.matches(payoutId, idempotencyKey, PayoutOperatorActionType.MANUAL_PAYOUT_CONFIRMED, actor,
                evidenceReference.trim())) {
            return payouts.find(payoutId);
        }
        PayoutResponse.SellerPayout confirmed = payouts.confirm(payoutId);
        requireNoProviderOutbox(payoutId);
        recordAction(payoutId, idempotencyKey, PayoutOperatorActionType.MANUAL_PAYOUT_CONFIRMED, actor, evidenceReference.trim());
        return confirmed;
    }

    @Transactional
    public PayoutResponse.SellerPayout failBeforeDispatch(UUID payoutId, String idempotencyKey,
            String actor, String reason) {
        validate(payoutId, idempotencyKey, actor, reason);
        requireNoProviderOutbox(payoutId);
        if (actions.matches(payoutId, idempotencyKey, PayoutOperatorActionType.MANUAL_PAYOUT_FAILED, actor, reason.trim())) {
            return payouts.find(payoutId);
        }
        PayoutResponse.SellerPayout failed = payouts.fail(payoutId, "MANUAL_NO_TRANSFER");
        requireNoProviderOutbox(payoutId);
        recordAction(payoutId, idempotencyKey, PayoutOperatorActionType.MANUAL_PAYOUT_FAILED, actor, reason.trim());
        return failed;
    }

    @Transactional
    public PayoutResponse.SellerPayout reconcileNoTransfer(UUID payoutId, String idempotencyKey,
            String actor, String evidenceReference) {
        validate(payoutId, idempotencyKey, actor, evidenceReference);
        requireNoProviderOutbox(payoutId);
        PayoutResponse.SellerPayout payout = payouts.find(payoutId);
        if (payout.state() == SettlementPayoutState.FAILED
                && actions.matches(payoutId, idempotencyKey, PayoutOperatorActionType.MANUAL_NO_TRANSFER_CONFIRMED,
                        actor, evidenceReference.trim())) {
            return payout;
        }
        if (payout.state() != SettlementPayoutState.SENT
                && payout.state() != SettlementPayoutState.NEEDS_REVIEW) {
            throw conflict("Only a legacy attempted payout can be manually reconciled");
        }
        PayoutResponse.SellerPayout failed = payouts.failWithProviderEvidence(payoutId, "MANUAL_NO_TRANSFER_CONFIRMED");
        requireNoProviderOutbox(payoutId);
        recordAction(payoutId, idempotencyKey, PayoutOperatorActionType.MANUAL_NO_TRANSFER_CONFIRMED, actor,
                evidenceReference.trim());
        return failed;
    }

    @Transactional
    public PayoutResponse.SellerPayout resolveProviderReview(UUID payoutId, String idempotencyKey,
            String actor, SettlementHttpRequest.PayoutProviderResolution request) {
        if (request == null) throw invalid("Provider resolution evidence is required");
        validate(payoutId, idempotencyKey, actor, request.evidenceReference());
        PayoutDispatchRecord record = dispatches.find(payoutId)
                .orElseThrow(() -> conflict("Payout has no provider dispatch record"));
        if ((request.providerBatchId() != null
                && !request.providerBatchId().matches("[A-Za-z0-9_-]{1,128}"))
                || (request.providerItemId() != null
                && !request.providerItemId().matches("[A-Za-z0-9_-]{1,128}"))) {
            throw invalid("Provider evidence identifiers are invalid");
        }
        if (request.outcome() == SettlementHttpRequest.ProviderOutcome.NOT_PAID
                && (request.providerAmountMinor() != null || !"USD".equals(request.providerCurrency())
                || !Long.valueOf(0).equals(request.providerFeeMinor()))) {
            throw invalid("No-transfer resolution requires explicit zero-fee USD evidence");
        }
        boolean paid = request.outcome() == SettlementHttpRequest.ProviderOutcome.PAID;
        PayoutOperatorActionType action = paid
                ? PayoutOperatorActionType.PROVIDER_REVIEW_PAID : PayoutOperatorActionType.PROVIDER_REVIEW_NOT_PAID;
        PayoutResponse.SellerPayout payout = payouts.find(payoutId);
        if (record.status() == PayoutDispatchState.COMPLETED) {
            if (action != PayoutOperatorActionType.PROVIDER_REVIEW_PAID
                    || !actions.matches(payoutId, idempotencyKey, action, actor, request.evidenceReference().trim())) {
                throw conflict("Provider review has already been resolved");
            }
            validatePaidProviderFacts(record, request);
            long feeWon = conversions.usdCentsToWonCeiling(request.providerFeeMinor(), record.fxRateWonPerUsd());
            if (!Objects.equals(record.providerFeeMinor(), request.providerFeeMinor())
                    || !Objects.equals(record.providerFeeWon(), feeWon)) {
                throw conflict("Provider fee evidence differs from the completed payout");
            }
            return payout;
        }
        if (record.status() == PayoutDispatchState.FAILED) {
            if (action != PayoutOperatorActionType.PROVIDER_REVIEW_NOT_PAID
                    || !actions.matches(payoutId, idempotencyKey, action, actor, request.evidenceReference().trim())
                    || !Long.valueOf(0).equals(record.providerFeeMinor())
                    || !"USD".equals(record.providerFeeCurrency())
                    || !Long.valueOf(0).equals(record.providerFeeWon())
                    || (record.providerBatchId() != null && request.providerBatchId() != null
                    && !record.providerBatchId().equals(request.providerBatchId()))
                    || (record.providerItemId() != null && request.providerItemId() != null
                    && !record.providerItemId().equals(request.providerItemId()))) {
                throw conflict("Provider review has already been resolved");
            }
            return payout;
        }
        if (record.status() != PayoutDispatchState.NEEDS_REVIEW || payout.state() != SettlementPayoutState.NEEDS_REVIEW) {
            throw conflict("Only a payout in provider review can be manually resolved");
        }
        if (paid) {
            validatePaidProviderFacts(record, request);
            long feeWon = conversions.usdCentsToWonCeiling(
                    request.providerFeeMinor(), record.fxRateWonPerUsd());
            PayoutResponse.SellerPayout confirmed = payouts.confirmProviderSuccess(payoutId,
                    record.providerDebitBookValueWon(), record.payoutFxRoundingExpenseWon(), feeWon);
            if (!dispatches.resolveNeedsReview(payoutId, PayoutDispatchState.COMPLETED, request.providerBatchId(),
                    request.providerItemId(), request.providerFeeMinor(), feeWon, "OPERATOR_PROVIDER_PAID")) {
                throw conflict("Provider outbox changed while manual evidence was applied");
            }
            recordAction(payoutId, idempotencyKey, action, actor, request.evidenceReference().trim());
            return confirmed;
        }
        if (record.providerBatchId() != null && request.providerBatchId() != null
                && !record.providerBatchId().equals(request.providerBatchId())) {
            throw conflict("Provider batch evidence does not match the payout");
        }
        if (record.providerItemId() != null && request.providerItemId() != null
                && !record.providerItemId().equals(request.providerItemId())) {
            throw conflict("Provider item evidence does not match the payout");
        }
        PayoutResponse.SellerPayout failed = payouts.failWithProviderEvidence(
                payoutId, "OPERATOR_CONFIRMED_NO_TRANSFER");
        if (!dispatches.resolveNeedsReview(payoutId, PayoutDispatchState.FAILED, request.providerBatchId(),
                request.providerItemId(), 0L, 0L, "OPERATOR_NO_TRANSFER")) {
            throw conflict("Provider outbox changed while manual evidence was applied");
        }
        recordAction(payoutId, idempotencyKey, action, actor, request.evidenceReference().trim());
        return failed;
    }

    private static void validatePaidProviderFacts(PayoutDispatchRecord record,
            SettlementHttpRequest.PayoutProviderResolution request) {
        if (request.providerAmountMinor() == null || request.providerFeeMinor() == null
                || request.providerBatchId() == null || request.providerItemId() == null
                || request.providerFeeMinor() < 0
                || !request.providerBatchId().matches("[A-Za-z0-9_-]{1,128}")
                || !request.providerItemId().matches("[A-Za-z0-9_-]{1,128}")
                || !request.providerAmountMinor().equals(record.providerAmountMinor())
                || !"USD".equals(request.providerCurrency())
                || record.providerBatchId() != null && !record.providerBatchId().equals(request.providerBatchId())
                || record.providerItemId() != null && !record.providerItemId().equals(request.providerItemId())) {
            throw conflict("Manual provider evidence does not match immutable payout facts");
        }
    }

    private void recordAction(UUID payoutId, String idempotencyKey, PayoutOperatorActionType action,
            String actor, String reason) {
        if (!actions.record(payoutId, idempotencyKey, action, actor, reason, clock.instant())) {
            throw conflict("Payout operator idempotency key was reused for different facts");
        }
    }
    private void requireNoProviderOutbox(UUID payoutId) {
        if (dispatches.find(payoutId).isPresent()) {
            throw conflict("Provider-managed payout cannot use the manual payout path");
        }
    }

    private static void validate(UUID payoutId, String idempotencyKey, String actor, String reason) {
        if (payoutId == null || !StringUtils.hasText(idempotencyKey) || idempotencyKey.length() > 128
                || !StringUtils.hasText(actor) || actor.length() > 128 || !StringUtils.hasText(reason)
                || reason.length() > 500 || !reason.matches("[A-Za-z0-9가-힣 .,/_:-]+")) {
            throw new SettlementException(SettlementException.Code.INVALID_REQUEST,
                    "Payout, idempotency key, operator and evidence/reason are required");
        }
    }

    private static SettlementException invalid(String message) {
        return new SettlementException(SettlementException.Code.INVALID_REQUEST, message);
    }
    private static SettlementException conflict(String message) {
        return new SettlementException(SettlementException.Code.CONFLICT, message);
    }
}
