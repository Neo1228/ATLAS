package com.atlas.application.service.payout;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.atlas.application.security.PayoutRecipientCipher;
import com.atlas.settlement.dto.response.PayoutResponse;
import com.atlas.settlement.exception.SettlementException;
import com.atlas.settlement.model.PayoutDispatchPlan;
import com.atlas.settlement.model.PayoutDispatchRecord;
import com.atlas.settlement.model.PayoutDispatchState;
import com.atlas.settlement.model.SettlementPayoutState;
import com.atlas.settlement.service.PayoutDispatchStore;
import com.atlas.settlement.service.PayoutOperatorActionStore;
import com.atlas.settlement.service.SettlementPayoutService;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.InOrder;

class PayoutDispatchApprovalCommitServiceTest {
    private static final Instant NOW = Instant.parse("2026-11-24T03:00:00Z");
    private static final String CIPHERTEXT = "v2:active:opaque-ciphertext";
    private static final String EMAIL = "seller@example.test";
    private static final long AMOUNT_WON = 130_000L;
    private static final String KEY = "key-1";
    private static final String ACTOR = "operator-1";
    private static final String REASON = "manual decision";

    private final SettlementPayoutService payouts = mock(SettlementPayoutService.class);
    private final SellerPayoutProfileService profiles = mock(SellerPayoutProfileService.class);
    private final PayoutRecipientCipher cipher = mock(PayoutRecipientCipher.class);
    private final PayoutDispatchStore dispatches = mock(PayoutDispatchStore.class);
    private final PayoutOperatorActionStore actions = mock(PayoutOperatorActionStore.class);
    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
    private final PayoutDispatchApprovalCommitService service = new PayoutDispatchApprovalCommitService(
            payouts, profiles, cipher, dispatches, actions, clock);

    private final UUID payoutId = UUID.randomUUID();
    private final UUID sellerId = UUID.randomUUID();
    private final UUID accountId = UUID.randomUUID();

    @Test
    void approveRequestedPayoutApprovesThenEnqueuesThenRecordsAuditActionWithClockInstant() {
        PayoutDispatchPlan plan = plan();
        PayoutResponse.SellerPayout approved = payout(SettlementPayoutState.APPROVED);
        stubApprovable(SettlementPayoutState.REQUESTED, approved, plan);
        when(actions.record(payoutId, KEY, "PAYOUT_APPROVED", ACTOR, REASON, NOW)).thenReturn(true);

        PayoutResponse.SellerPayout result = service.approve(payoutId, plan, recipient(3, EMAIL), KEY, ACTOR, REASON);

        assertThat(result).isSameAs(approved);
        InOrder order = inOrder(payouts, dispatches, actions);
        order.verify(payouts).approve(payoutId);
        order.verify(dispatches).enqueue(plan);
        order.verify(actions).record(payoutId, KEY, "PAYOUT_APPROVED", ACTOR, REASON, NOW);
    }

    @Test
    void approveAlreadyApprovedPayoutNeverApprovesAgainButStillConvergesOnTheSameDispatch() {
        PayoutDispatchPlan plan = plan();
        PayoutResponse.SellerPayout approved = payout(SettlementPayoutState.APPROVED);
        when(payouts.find(payoutId)).thenReturn(approved);
        when(profiles.requireActiveRecipient(sellerId)).thenReturn(recipient(3, EMAIL));
        when(dispatches.enqueue(plan)).thenReturn(dispatchRecord(PayoutDispatchState.PENDING, plan.requestPayloadSha256()));
        when(cipher.decrypt(CIPHERTEXT)).thenReturn(EMAIL);
        when(actions.record(payoutId, KEY, "PAYOUT_APPROVED", ACTOR, REASON, NOW)).thenReturn(true);

        PayoutResponse.SellerPayout result = service.approve(payoutId, plan, recipient(3, EMAIL), KEY, ACTOR, REASON);

        assertThat(result).isSameAs(approved);
        verify(payouts, never()).approve(any());
        verify(dispatches).enqueue(plan);
    }

    @Test
    void approveAcceptsEnqueuedFactsWhoseFxRateDiffersOnlyByScale() {
        PayoutDispatchPlan plan = plan();
        stubApprovable(SettlementPayoutState.REQUESTED, payout(SettlementPayoutState.APPROVED), plan);
        when(actions.record(payoutId, KEY, "PAYOUT_APPROVED", ACTOR, REASON, NOW)).thenReturn(true);

        service.approve(payoutId, plan, recipient(3, EMAIL), KEY, ACTOR, REASON);

        verify(actions).record(payoutId, KEY, "PAYOUT_APPROVED", ACTOR, REASON, NOW);
    }

    @Test
    void approveRejectsPlanForAnotherPayoutBeforeTouchingAnyStore() {
        PayoutDispatchPlan plan = plan();

        assertThatThrownBy(() -> service.approve(UUID.randomUUID(), plan, recipient(3, EMAIL), KEY, ACTOR, REASON))
                .isInstanceOfSatisfying(SettlementException.class,
                        e -> assertThat(e.code()).isEqualTo(SettlementException.Code.INVALID_REQUEST));

        verify(payouts, never()).find(any());
        verify(payouts, never()).approve(any());
        verify(dispatches, never()).enqueue(any());
        verify(actions, never()).record(any(), anyString(), anyString(), anyString(), anyString(), any());
    }

    @ParameterizedTest
    @EnumSource(value = SettlementPayoutState.class, names = {"REQUESTED", "APPROVED"}, mode = EnumSource.Mode.EXCLUDE)
    void approveRejectsPayoutThatIsNeitherRequestedNorApproved(SettlementPayoutState state) {
        PayoutDispatchPlan plan = plan();
        when(payouts.find(payoutId)).thenReturn(payout(state));

        assertThatThrownBy(() -> service.approve(payoutId, plan, recipient(3, EMAIL), KEY, ACTOR, REASON))
                .isInstanceOfSatisfying(SettlementException.class,
                        e -> assertThat(e.code()).isEqualTo(SettlementException.Code.CONFLICT));

        verify(payouts, never()).approve(any());
        verify(dispatches, never()).enqueue(any());
        verify(actions, never()).record(any(), anyString(), anyString(), anyString(), anyString(), any());
    }

    @Test
    void approveRejectsPlanWhoseAmountDiffersFromThePayoutAmount() {
        PayoutDispatchPlan plan = plan();
        when(payouts.find(payoutId)).thenReturn(payout(SettlementPayoutState.REQUESTED, AMOUNT_WON + 1, sellerId));

        assertThatThrownBy(() -> service.approve(payoutId, plan, recipient(3, EMAIL), KEY, ACTOR, REASON))
                .isInstanceOfSatisfying(SettlementException.class,
                        e -> assertThat(e.code()).isEqualTo(SettlementException.Code.CONFLICT));

        verify(profiles, never()).requireActiveRecipient(any());
        verify(payouts, never()).approve(any());
        verify(dispatches, never()).enqueue(any());
    }

    @Test
    void approveRejectsExpectedRecipientBelongingToAnotherSeller() {
        PayoutDispatchPlan plan = plan();
        when(payouts.find(payoutId)).thenReturn(payout(SettlementPayoutState.REQUESTED, AMOUNT_WON, UUID.randomUUID()));

        assertThatThrownBy(() -> service.approve(payoutId, plan, recipient(3, EMAIL), KEY, ACTOR, REASON))
                .isInstanceOfSatisfying(SettlementException.class,
                        e -> assertThat(e.code()).isEqualTo(SettlementException.Code.CONFLICT));

        verify(profiles, never()).requireActiveRecipient(any());
        verify(payouts, never()).approve(any());
        verify(dispatches, never()).enqueue(any());
    }

    @Test
    void approveRejectsWhenSellerProfileVersionChangedSincePlanWasPrepared() {
        PayoutDispatchPlan plan = plan();
        when(payouts.find(payoutId)).thenReturn(payout(SettlementPayoutState.REQUESTED));
        when(profiles.requireActiveRecipient(sellerId)).thenReturn(recipient(4, EMAIL));

        assertThatThrownBy(() -> service.approve(payoutId, plan, recipient(3, EMAIL), KEY, ACTOR, REASON))
                .isInstanceOfSatisfying(SettlementException.class,
                        e -> assertThat(e.code()).isEqualTo(SettlementException.Code.CONFLICT));

        verify(payouts, never()).approve(any());
        verify(dispatches, never()).enqueue(any());
    }

    @Test
    void approveRejectsWhenVerifiedEmailChangedSincePlanWasPrepared() {
        PayoutDispatchPlan plan = plan();
        when(payouts.find(payoutId)).thenReturn(payout(SettlementPayoutState.REQUESTED));
        when(profiles.requireActiveRecipient(sellerId)).thenReturn(recipient(3, "other@example.test"));

        assertThatThrownBy(() -> service.approve(payoutId, plan, recipient(3, EMAIL), KEY, ACTOR, REASON))
                .isInstanceOfSatisfying(SettlementException.class,
                        e -> assertThat(e.code()).isEqualTo(SettlementException.Code.CONFLICT));

        verify(payouts, never()).approve(any());
        verify(dispatches, never()).enqueue(any());
    }

    @Test
    void approveRejectsWhenPayoutDoesNotReachApprovedState() {
        PayoutDispatchPlan plan = plan();
        when(payouts.find(payoutId)).thenReturn(payout(SettlementPayoutState.REQUESTED));
        when(profiles.requireActiveRecipient(sellerId)).thenReturn(recipient(3, EMAIL));
        when(payouts.approve(payoutId)).thenReturn(payout(SettlementPayoutState.REQUESTED));

        assertThatThrownBy(() -> service.approve(payoutId, plan, recipient(3, EMAIL), KEY, ACTOR, REASON))
                .isInstanceOfSatisfying(SettlementException.class,
                        e -> assertThat(e.code()).isEqualTo(SettlementException.Code.CONFLICT));

        verify(dispatches, never()).enqueue(any());
        verify(actions, never()).record(any(), anyString(), anyString(), anyString(), anyString(), any());
    }

    @Test
    void approveRejectsWhenExistingDispatchHasDifferentImmutableFacts() {
        PayoutDispatchPlan plan = plan();
        stubApprovable(SettlementPayoutState.REQUESTED, payout(SettlementPayoutState.APPROVED), plan);
        when(dispatches.enqueue(plan)).thenReturn(dispatchRecord(PayoutDispatchState.PENDING, "b".repeat(64)));

        assertThatThrownBy(() -> service.approve(payoutId, plan, recipient(3, EMAIL), KEY, ACTOR, REASON))
                .isInstanceOfSatisfying(SettlementException.class,
                        e -> assertThat(e.code()).isEqualTo(SettlementException.Code.CONFLICT));

        verify(cipher, never()).decrypt(anyString());
        verify(actions, never()).record(any(), anyString(), anyString(), anyString(), anyString(), any());
    }

    @Test
    void approveRejectsWhenStoredRecipientCiphertextDecryptsToDifferentEmail() {
        PayoutDispatchPlan plan = plan();
        stubApprovable(SettlementPayoutState.REQUESTED, payout(SettlementPayoutState.APPROVED), plan);
        when(cipher.decrypt(CIPHERTEXT)).thenReturn("stale@example.test");

        assertThatThrownBy(() -> service.approve(payoutId, plan, recipient(3, EMAIL), KEY, ACTOR, REASON))
                .isInstanceOfSatisfying(SettlementException.class,
                        e -> assertThat(e.code()).isEqualTo(SettlementException.Code.CONFLICT));

        verify(actions, never()).record(any(), anyString(), anyString(), anyString(), anyString(), any());
    }

    @Test
    void approveFailsWhenOperatorIdempotencyKeyWasReusedForDifferentFacts() {
        PayoutDispatchPlan plan = plan();
        stubApprovable(SettlementPayoutState.REQUESTED, payout(SettlementPayoutState.APPROVED), plan);
        when(actions.record(payoutId, KEY, "PAYOUT_APPROVED", ACTOR, REASON, NOW)).thenReturn(false);

        assertThatThrownBy(() -> service.approve(payoutId, plan, recipient(3, EMAIL), KEY, ACTOR, REASON))
                .isInstanceOfSatisfying(SettlementException.class,
                        e -> assertThat(e.code()).isEqualTo(SettlementException.Code.CONFLICT));
    }

    @Test
    void replayReturnsPayoutWithoutRecordingAgainWhenSameApprovalActionAlreadyExists() {
        PayoutResponse.SellerPayout approved = payout(SettlementPayoutState.APPROVED);
        when(payouts.find(payoutId)).thenReturn(approved);
        when(actions.matches(payoutId, KEY, "PAYOUT_APPROVED", ACTOR, REASON)).thenReturn(true);

        PayoutResponse.SellerPayout result = service.replay(payoutId, KEY, ACTOR, REASON);

        assertThat(result).isSameAs(approved);
        verify(actions, never()).record(any(), anyString(), anyString(), anyString(), anyString(), any());
        verify(dispatches, never()).enqueue(any());
        verify(payouts, never()).approve(any());
    }

    @Test
    void replayOfApprovedPayoutRecordsReplayActionWithClockInstant() {
        PayoutResponse.SellerPayout approved = payout(SettlementPayoutState.APPROVED);
        when(payouts.find(payoutId)).thenReturn(approved);
        when(actions.matches(payoutId, KEY, "PAYOUT_APPROVED", ACTOR, REASON)).thenReturn(false);
        when(actions.record(payoutId, KEY, "PAYOUT_APPROVAL_REPLAYED", ACTOR, REASON, NOW)).thenReturn(true);

        PayoutResponse.SellerPayout result = service.replay(payoutId, KEY, ACTOR, REASON);

        assertThat(result).isSameAs(approved);
        verify(actions).record(payoutId, KEY, "PAYOUT_APPROVAL_REPLAYED", ACTOR, REASON, NOW);
        verify(dispatches, never()).enqueue(any());
    }

    @ParameterizedTest
    @EnumSource(value = SettlementPayoutState.class, names = "APPROVED", mode = EnumSource.Mode.EXCLUDE)
    void replayRejectsPayoutThatLeftApprovedStateWithoutMatchingApprovalAction(SettlementPayoutState state) {
        when(payouts.find(payoutId)).thenReturn(payout(state));
        when(actions.matches(payoutId, KEY, "PAYOUT_APPROVED", ACTOR, REASON)).thenReturn(false);

        assertThatThrownBy(() -> service.replay(payoutId, KEY, ACTOR, REASON))
                .isInstanceOfSatisfying(SettlementException.class,
                        e -> assertThat(e.code()).isEqualTo(SettlementException.Code.CONFLICT));

        verify(actions, never()).record(any(), anyString(), anyString(), anyString(), anyString(), any());
    }

    @Test
    void cancelCancelsPayoutBeforeDispatchThenCancelsPendingDispatchThenRecordsAction() {
        PayoutResponse.SellerPayout cancelled = payout(SettlementPayoutState.CANCELLED);
        when(payouts.cancelBeforeDispatch(payoutId, "OPERATOR_CANCELLED")).thenReturn(cancelled);
        when(dispatches.cancelPending(payoutId)).thenReturn(true);
        when(actions.record(payoutId, KEY, "PAYOUT_CANCELLED", ACTOR, REASON, NOW)).thenReturn(true);

        PayoutResponse.SellerPayout result = service.cancel(payoutId, KEY, ACTOR, REASON);

        assertThat(result).isSameAs(cancelled);
        InOrder order = inOrder(payouts, dispatches, actions);
        order.verify(payouts).cancelBeforeDispatch(payoutId, "OPERATOR_CANCELLED");
        order.verify(dispatches).cancelPending(payoutId);
        order.verify(actions).record(payoutId, KEY, "PAYOUT_CANCELLED", ACTOR, REASON, NOW);
    }

    @Test
    void cancelStopsBeforeTouchingDispatchOrAuditWhenPayoutCannotBeCancelled() {
        SettlementException conflict = new SettlementException(SettlementException.Code.CONFLICT, "already sent");
        when(payouts.cancelBeforeDispatch(payoutId, "OPERATOR_CANCELLED")).thenThrow(conflict);

        assertThatThrownBy(() -> service.cancel(payoutId, KEY, ACTOR, REASON)).isSameAs(conflict);

        verify(dispatches, never()).cancelPending(any());
        verify(actions, never()).record(any(), anyString(), anyString(), anyString(), anyString(), any());
    }

    @Test
    void cancelFailsWhenOperatorIdempotencyKeyWasReusedForDifferentFacts() {
        when(payouts.cancelBeforeDispatch(payoutId, "OPERATOR_CANCELLED"))
                .thenReturn(payout(SettlementPayoutState.CANCELLED));
        when(actions.record(payoutId, KEY, "PAYOUT_CANCELLED", ACTOR, REASON, NOW)).thenReturn(false);

        assertThatThrownBy(() -> service.cancel(payoutId, KEY, ACTOR, REASON))
                .isInstanceOfSatisfying(SettlementException.class,
                        e -> assertThat(e.code()).isEqualTo(SettlementException.Code.CONFLICT));
    }

    @Test
    void requeueNeedsReviewRequeuesDispatchAndRecordsActionWhenNoActionWasRecordedYet() {
        PayoutResponse.SellerPayout review = payout(SettlementPayoutState.NEEDS_REVIEW);
        when(payouts.find(payoutId)).thenReturn(review);
        when(dispatches.find(payoutId)).thenReturn(Optional.of(dispatchRecord(PayoutDispatchState.NEEDS_REVIEW, sha())));
        when(actions.matches(payoutId, KEY, "PAYOUT_REQUEUED", ACTOR, REASON)).thenReturn(false);
        when(dispatches.requeueNeedsReview(payoutId)).thenReturn(true);
        when(actions.record(payoutId, KEY, "PAYOUT_REQUEUED", ACTOR, REASON, NOW)).thenReturn(true);

        PayoutResponse.SellerPayout result = service.requeueNeedsReview(payoutId, KEY, ACTOR, REASON);

        assertThat(result).isSameAs(review);
        InOrder order = inOrder(dispatches, actions);
        order.verify(dispatches).requeueNeedsReview(payoutId);
        order.verify(actions).record(payoutId, KEY, "PAYOUT_REQUEUED", ACTOR, REASON, NOW);
    }

    @Test
    void requeueNeedsReviewIsIdempotentWhenSameRequeueActionAlreadyRecorded() {
        PayoutResponse.SellerPayout review = payout(SettlementPayoutState.NEEDS_REVIEW);
        when(payouts.find(payoutId)).thenReturn(review);
        when(dispatches.find(payoutId)).thenReturn(Optional.of(dispatchRecord(PayoutDispatchState.NEEDS_REVIEW, sha())));
        when(actions.matches(payoutId, KEY, "PAYOUT_REQUEUED", ACTOR, REASON)).thenReturn(true);

        PayoutResponse.SellerPayout result = service.requeueNeedsReview(payoutId, KEY, ACTOR, REASON);

        assertThat(result).isSameAs(review);
        verify(dispatches, never()).requeueNeedsReview(any());
        verify(actions, never()).record(any(), anyString(), anyString(), anyString(), anyString(), any());
    }

    @Test
    void requeueNeedsReviewRejectsWhenDispatchHasNoRecord() {
        when(payouts.find(payoutId)).thenReturn(payout(SettlementPayoutState.NEEDS_REVIEW));
        when(dispatches.find(payoutId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.requeueNeedsReview(payoutId, KEY, ACTOR, REASON))
                .isInstanceOfSatisfying(SettlementException.class,
                        e -> assertThat(e.code()).isEqualTo(SettlementException.Code.CONFLICT));

        verify(dispatches, never()).requeueNeedsReview(any());
        verify(actions, never()).record(any(), anyString(), anyString(), anyString(), anyString(), any());
    }

    @ParameterizedTest
    @EnumSource(value = SettlementPayoutState.class, names = "NEEDS_REVIEW", mode = EnumSource.Mode.EXCLUDE)
    void requeueNeedsReviewRejectsDispatchInReviewWhenPayoutIsNotInReviewState(SettlementPayoutState state) {
        when(payouts.find(payoutId)).thenReturn(payout(state));
        when(dispatches.find(payoutId)).thenReturn(Optional.of(dispatchRecord(PayoutDispatchState.NEEDS_REVIEW, sha())));
        when(actions.matches(payoutId, KEY, "PAYOUT_REQUEUED", ACTOR, REASON)).thenReturn(false);

        assertThatThrownBy(() -> service.requeueNeedsReview(payoutId, KEY, ACTOR, REASON))
                .isInstanceOfSatisfying(SettlementException.class,
                        e -> assertThat(e.code()).isEqualTo(SettlementException.Code.CONFLICT));

        verify(dispatches, never()).requeueNeedsReview(any());
        verify(actions, never()).record(any(), anyString(), anyString(), anyString(), anyString(), any());
    }

    @Test
    void requeueNeedsReviewReturnsPayoutWhenConcurrentRequeueMovedDispatchBackToPendingWithSameAction() {
        PayoutResponse.SellerPayout review = payout(SettlementPayoutState.NEEDS_REVIEW);
        when(payouts.find(payoutId)).thenReturn(review);
        when(dispatches.find(payoutId)).thenReturn(Optional.of(dispatchRecord(PayoutDispatchState.NEEDS_REVIEW, sha())))
                .thenReturn(Optional.of(dispatchRecord(PayoutDispatchState.PENDING, sha())));
        when(actions.matches(payoutId, KEY, "PAYOUT_REQUEUED", ACTOR, REASON)).thenReturn(false, true);
        when(dispatches.requeueNeedsReview(payoutId)).thenReturn(false);

        PayoutResponse.SellerPayout result = service.requeueNeedsReview(payoutId, KEY, ACTOR, REASON);

        assertThat(result).isSameAs(review);
        verify(actions, never()).record(any(), anyString(), anyString(), anyString(), anyString(), any());
    }

    @Test
    void requeueNeedsReviewRejectsWhenRequeueLostRaceAndDispatchIsNoLongerWaiting() {
        when(payouts.find(payoutId)).thenReturn(payout(SettlementPayoutState.NEEDS_REVIEW));
        when(dispatches.find(payoutId)).thenReturn(Optional.of(dispatchRecord(PayoutDispatchState.NEEDS_REVIEW, sha())))
                .thenReturn(Optional.of(dispatchRecord(PayoutDispatchState.COMPLETED, sha())));
        when(actions.matches(payoutId, KEY, "PAYOUT_REQUEUED", ACTOR, REASON)).thenReturn(false);
        when(dispatches.requeueNeedsReview(payoutId)).thenReturn(false);

        assertThatThrownBy(() -> service.requeueNeedsReview(payoutId, KEY, ACTOR, REASON))
                .isInstanceOfSatisfying(SettlementException.class,
                        e -> assertThat(e.code()).isEqualTo(SettlementException.Code.CONFLICT));

        verify(actions, never()).record(any(), anyString(), anyString(), anyString(), anyString(), any());
    }

    @Test
    void requeueNeedsReviewRejectsWhenRequeueLostRaceAndDispatchIsPendingWithoutMatchingAction() {
        when(payouts.find(payoutId)).thenReturn(payout(SettlementPayoutState.NEEDS_REVIEW));
        when(dispatches.find(payoutId)).thenReturn(Optional.of(dispatchRecord(PayoutDispatchState.NEEDS_REVIEW, sha())))
                .thenReturn(Optional.of(dispatchRecord(PayoutDispatchState.PENDING, sha())));
        when(actions.matches(payoutId, KEY, "PAYOUT_REQUEUED", ACTOR, REASON)).thenReturn(false);
        when(dispatches.requeueNeedsReview(payoutId)).thenReturn(false);

        assertThatThrownBy(() -> service.requeueNeedsReview(payoutId, KEY, ACTOR, REASON))
                .isInstanceOfSatisfying(SettlementException.class,
                        e -> assertThat(e.code()).isEqualTo(SettlementException.Code.CONFLICT));
    }

    @Test
    void requeueNeedsReviewRejectsWhenDispatchDisappearedDuringRequeue() {
        when(payouts.find(payoutId)).thenReturn(payout(SettlementPayoutState.NEEDS_REVIEW));
        when(dispatches.find(payoutId)).thenReturn(Optional.of(dispatchRecord(PayoutDispatchState.NEEDS_REVIEW, sha())))
                .thenReturn(Optional.empty());
        when(actions.matches(payoutId, KEY, "PAYOUT_REQUEUED", ACTOR, REASON)).thenReturn(false);
        when(dispatches.requeueNeedsReview(payoutId)).thenReturn(false);

        assertThatThrownBy(() -> service.requeueNeedsReview(payoutId, KEY, ACTOR, REASON))
                .isInstanceOfSatisfying(SettlementException.class,
                        e -> assertThat(e.code()).isEqualTo(SettlementException.Code.CONFLICT));
    }

    @ParameterizedTest
    @EnumSource(value = SettlementPayoutState.class, names = {"APPROVED", "NEEDS_REVIEW"})
    void requeueNeedsReviewReturnsPayoutForAlreadyWaitingDispatchWhenSameActionWasRecorded(
            SettlementPayoutState state) {
        PayoutResponse.SellerPayout payout = payout(state);
        when(payouts.find(payoutId)).thenReturn(payout);
        when(dispatches.find(payoutId)).thenReturn(Optional.of(dispatchRecord(PayoutDispatchState.WAITING_PROVIDER, sha())));
        when(actions.matches(payoutId, KEY, "PAYOUT_REQUEUED", ACTOR, REASON)).thenReturn(true);

        PayoutResponse.SellerPayout result = service.requeueNeedsReview(payoutId, KEY, ACTOR, REASON);

        assertThat(result).isSameAs(payout);
        verify(dispatches, never()).requeueNeedsReview(any());
    }

    @Test
    void requeueNeedsReviewRejectsPendingDispatchWhenNoRequeueActionWasRecorded() {
        when(payouts.find(payoutId)).thenReturn(payout(SettlementPayoutState.APPROVED));
        when(dispatches.find(payoutId)).thenReturn(Optional.of(dispatchRecord(PayoutDispatchState.PENDING, sha())));
        when(actions.matches(payoutId, KEY, "PAYOUT_REQUEUED", ACTOR, REASON)).thenReturn(false);

        assertThatThrownBy(() -> service.requeueNeedsReview(payoutId, KEY, ACTOR, REASON))
                .isInstanceOfSatisfying(SettlementException.class,
                        e -> assertThat(e.code()).isEqualTo(SettlementException.Code.CONFLICT));

        verify(dispatches, never()).requeueNeedsReview(any());
        verify(actions, never()).record(any(), anyString(), anyString(), anyString(), anyString(), any());
    }

    @Test
    void requeueNeedsReviewRejectsDispatchInTerminalStatus() {
        when(payouts.find(payoutId)).thenReturn(payout(SettlementPayoutState.CONFIRMED));
        when(dispatches.find(payoutId)).thenReturn(Optional.of(dispatchRecord(PayoutDispatchState.COMPLETED, sha())));
        when(actions.matches(payoutId, KEY, "PAYOUT_REQUEUED", ACTOR, REASON)).thenReturn(false);

        assertThatThrownBy(() -> service.requeueNeedsReview(payoutId, KEY, ACTOR, REASON))
                .isInstanceOfSatisfying(SettlementException.class,
                        e -> assertThat(e.code()).isEqualTo(SettlementException.Code.CONFLICT));

        verify(dispatches, never()).requeueNeedsReview(any());
    }

    private void stubApprovable(SettlementPayoutState currentState, PayoutResponse.SellerPayout approved,
            PayoutDispatchPlan plan) {
        when(payouts.find(payoutId)).thenReturn(payout(currentState));
        when(profiles.requireActiveRecipient(sellerId)).thenReturn(recipient(3, EMAIL));
        when(payouts.approve(payoutId)).thenReturn(approved);
        when(dispatches.enqueue(plan)).thenReturn(dispatchRecord(PayoutDispatchState.PENDING, plan.requestPayloadSha256()));
        when(cipher.decrypt(CIPHERTEXT)).thenReturn(EMAIL);
    }

    private PayoutResponse.SellerPayout payout(SettlementPayoutState state) {
        return payout(state, AMOUNT_WON, sellerId);
    }

    private PayoutResponse.SellerPayout payout(SettlementPayoutState state, long amountWon, UUID owner) {
        Instant requestedAt = Instant.parse("2026-11-22T00:00:00Z");
        return new PayoutResponse.SellerPayout(payoutId, owner, requestedAt, requestedAt.plusSeconds(60), amountWon, 0,
                state, requestedAt, null, null, null);
    }

    private SellerPayoutProfileService.ActiveRecipient recipient(int profileVersion, String email) {
        return new SellerPayoutProfileService.ActiveRecipient(sellerId, accountId, profileVersion, "US", "USD",
                email);
    }

    private String sha() {
        return "a".repeat(64);
    }

    private PayoutDispatchPlan plan() {
        return new PayoutDispatchPlan(payoutId, 3, CIPHERTEXT, "US", AMOUNT_WON, 9_500L,
                new BigDecimal("1368.4210"), "BOK_ECOS", LocalDate.parse("2026-11-21"), AMOUNT_WON + 10, 10,
                "batch-1", "item-1", "request-1", sha());
    }

    private PayoutDispatchRecord dispatchRecord(PayoutDispatchState status, String payloadSha256) {
        return new PayoutDispatchRecord(payoutId, 3, CIPHERTEXT, "US", "USD", AMOUNT_WON, 9_500L,
                new BigDecimal("1368.421000"), "BOK_ECOS", LocalDate.parse("2026-11-21"), AMOUNT_WON + 10, 10,
                null, null, null, "batch-1", "item-1", "request-1", payloadSha256,
                null, null, null, null, status, NOW, NOW, 0, null, null, null, null);
    }
}
