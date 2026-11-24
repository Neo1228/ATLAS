package com.atlas.application.service.payout;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.atlas.settlement.service.PayoutDispatchRecord;
import com.atlas.settlement.service.PayoutDispatchStore;
import com.atlas.settlement.service.SettlementPayoutService;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

class PayoutDispatchOutcomeServiceTest {
    private static final Instant NOW = Instant.parse("2026-11-22T00:00:00Z");
    private static final Duration DELAY = Duration.ofMinutes(5);
    private final PayoutDispatchStore dispatches = mock(PayoutDispatchStore.class);
    private final SettlementPayoutService payouts = mock(SettlementPayoutService.class);
    private final PayoutDispatchOutcomeService service = new PayoutDispatchOutcomeService(dispatches, payouts);
    private final UUID payoutId = UUID.randomUUID();

    @Test
    void completeRecordsUsdFeeEvidenceThenCompletesThenConfirmsPayoutWithStoredFxValues() {
        PayoutDispatchRecord record = record(null);
        when(dispatches.recordProviderEvidence(record, "batch-1", "SUCCESS", "item-1", "SUCCESS", 12L, "USD", 16L))
                .thenReturn(true);
        when(dispatches.transition(record, "COMPLETED", null)).thenReturn(true);

        boolean result = service.complete(record, "batch-1", "SUCCESS", "item-1", "SUCCESS", 12L, 16L);

        assertThat(result).isTrue();
        InOrder order = inOrder(dispatches, payouts);
        order.verify(dispatches).recordProviderEvidence(record, "batch-1", "SUCCESS", "item-1", "SUCCESS", 12L, "USD", 16L);
        order.verify(dispatches).transition(record, "COMPLETED", null);
        order.verify(payouts).confirmProviderSuccess(payoutId, 10_011L, 11L, 16L);
    }

    @Test
    void completeDoesNotTouchSettlementWhenEvidenceWasNotRecorded() {
        PayoutDispatchRecord record = record(null);
        when(dispatches.recordProviderEvidence(any(), anyString(), anyString(), anyString(), anyString(), any(), anyString(), any()))
                .thenReturn(false);

        boolean result = service.complete(record, "batch-1", "SUCCESS", "item-1", "SUCCESS", 12L, 16L);

        assertThat(result).isFalse();
        verify(dispatches, never()).transition(any(), anyString(), any());
        verifyNoInteractions(payouts);
    }

    @Test
    void completeDoesNotConfirmPayoutWhenDispatchAlreadyLeftTheLeasedState() {
        PayoutDispatchRecord record = record(null);
        when(dispatches.recordProviderEvidence(any(), anyString(), anyString(), anyString(), anyString(), any(), anyString(), any()))
                .thenReturn(true);
        when(dispatches.transition(record, "COMPLETED", null)).thenReturn(false);

        boolean result = service.complete(record, "batch-1", "SUCCESS", "item-1", "SUCCESS", 12L, 16L);

        assertThat(result).isFalse();
        verify(payouts, never()).confirmProviderSuccess(any(), anyLong(), anyLong(), anyLong());
    }

    @Test
    void failWithFeeRecordsUsdCurrencyAndFailsPayoutWithSafeCode() {
        PayoutDispatchRecord record = record("batch-1");
        when(dispatches.recordProviderEvidence(record, "batch-1", "DENIED", "item-1", "FAILED", 5L, "USD", 7L))
                .thenReturn(true);
        when(dispatches.transition(record, "FAILED", "PROVIDER_REJECTED")).thenReturn(true);

        boolean result = service.fail(record, "batch-1", "DENIED", "item-1", "FAILED", "PROVIDER_REJECTED", 5L, 7L);

        assertThat(result).isTrue();
        InOrder order = inOrder(dispatches, payouts);
        order.verify(dispatches).recordProviderEvidence(record, "batch-1", "DENIED", "item-1", "FAILED", 5L, "USD", 7L);
        order.verify(dispatches).transition(record, "FAILED", "PROVIDER_REJECTED");
        order.verify(payouts).failWithProviderEvidence(payoutId, "PROVIDER_REJECTED");
    }

    @Test
    void failWithoutFeeRecordsNullFeeCurrency() {
        PayoutDispatchRecord record = record("batch-1");
        when(dispatches.recordProviderEvidence(record, "batch-1", "DENIED", "item-1", "FAILED", null, null, null))
                .thenReturn(true);
        when(dispatches.transition(record, "FAILED", "PROVIDER_REJECTED")).thenReturn(true);

        boolean result = service.fail(record, "batch-1", "DENIED", "item-1", "FAILED", "PROVIDER_REJECTED", null, null);

        assertThat(result).isTrue();
        verify(dispatches).recordProviderEvidence(record, "batch-1", "DENIED", "item-1", "FAILED", null, null, null);
        verify(payouts).failWithProviderEvidence(payoutId, "PROVIDER_REJECTED");
    }

    @Test
    void failSkipsSettlementWhenDispatchWasAlreadyTerminal() {
        PayoutDispatchRecord record = record("batch-1");
        when(dispatches.recordProviderEvidence(any(), anyString(), anyString(), anyString(), anyString(), any(), any(), any()))
                .thenReturn(true);
        when(dispatches.transition(record, "FAILED", "PROVIDER_REJECTED")).thenReturn(false);

        boolean result = service.fail(record, "batch-1", "DENIED", "item-1", "FAILED", "PROVIDER_REJECTED", null, null);

        assertThat(result).isFalse();
        verifyNoInteractions(payouts);
    }

    @Test
    void failSkipsTransitionWhenEvidenceWasNotRecorded() {
        PayoutDispatchRecord record = record("batch-1");
        when(dispatches.recordProviderEvidence(any(), anyString(), anyString(), anyString(), anyString(), any(), any(), any()))
                .thenReturn(false);

        boolean result = service.fail(record, "batch-1", "DENIED", "item-1", "FAILED", "PROVIDER_REJECTED", null, null);

        assertThat(result).isFalse();
        verify(dispatches, never()).transition(any(), anyString(), any());
        verifyNoInteractions(payouts);
    }

    @Test
    void reviewMovesDispatchToNeedsReviewAndMarksPayoutForReview() {
        PayoutDispatchRecord record = record(null);
        when(dispatches.transition(record, "NEEDS_REVIEW", "PROVIDER_UNKNOWN")).thenReturn(true);

        boolean result = service.review(record, "PROVIDER_UNKNOWN");

        assertThat(result).isTrue();
        verify(payouts).markProviderNeedsReview(payoutId, "PROVIDER_UNKNOWN");
    }

    @Test
    void reviewDoesNotMarkPayoutWhenDispatchTransitionLost() {
        PayoutDispatchRecord record = record(null);
        when(dispatches.transition(record, "NEEDS_REVIEW", "PROVIDER_UNKNOWN")).thenReturn(false);

        boolean result = service.review(record, "PROVIDER_UNKNOWN");

        assertThat(result).isFalse();
        verifyNoInteractions(payouts);
    }

    @Test
    void cancelOnlyCancelsTheDispatchWithFixedSafeCodeAndLeavesPayoutUntouched() {
        PayoutDispatchRecord record = record(null);
        when(dispatches.transition(record, "CANCELLED", "PAYOUT_CANCELLED")).thenReturn(true);

        assertThat(service.cancel(record)).isTrue();
        verifyNoInteractions(payouts);
    }

    @Test
    void cancelReturnsFalseWhenDispatchCouldNotBeCancelled() {
        PayoutDispatchRecord record = record(null);
        when(dispatches.transition(record, "CANCELLED", "PAYOUT_CANCELLED")).thenReturn(false);

        assertThat(service.cancel(record)).isFalse();
        verifyNoInteractions(payouts);
    }

    @Test
    void unclaimedRecordsEvidenceReschedulesAsWaitingProviderAndMarksPayoutUnclaimed() {
        PayoutDispatchRecord record = record("batch-1");
        when(dispatches.recordProviderEvidence(record, "batch-1", "PENDING", "item-1", "UNCLAIMED", 3L, "USD", 4L))
                .thenReturn(true);
        when(dispatches.reschedule(record, DELAY, null, "WAITING_PROVIDER")).thenReturn(true);

        boolean result = service.unclaimed(record, "batch-1", "PENDING", "item-1", "UNCLAIMED", 3L, 4L, DELAY);

        assertThat(result).isTrue();
        InOrder order = inOrder(dispatches, payouts);
        order.verify(dispatches).recordProviderEvidence(record, "batch-1", "PENDING", "item-1", "UNCLAIMED", 3L, "USD", 4L);
        order.verify(dispatches).reschedule(record, DELAY, null, "WAITING_PROVIDER");
        order.verify(payouts).markProviderUnclaimed(payoutId);
    }

    @Test
    void unclaimedSkipsSettlementWhenRescheduleLost() {
        PayoutDispatchRecord record = record("batch-1");
        when(dispatches.recordProviderEvidence(any(), anyString(), anyString(), anyString(), anyString(), any(), any(), any()))
                .thenReturn(true);
        when(dispatches.reschedule(record, DELAY, null, "WAITING_PROVIDER")).thenReturn(false);

        boolean result = service.unclaimed(record, "batch-1", "PENDING", "item-1", "UNCLAIMED", null, null, DELAY);

        assertThat(result).isFalse();
        verifyNoInteractions(payouts);
    }

    @Test
    void unclaimedSkipsRescheduleWhenEvidenceWasNotRecorded() {
        PayoutDispatchRecord record = record("batch-1");
        when(dispatches.recordProviderEvidence(any(), anyString(), anyString(), anyString(), anyString(), any(), any(), any()))
                .thenReturn(false);

        boolean result = service.unclaimed(record, "batch-1", "PENDING", "item-1", "UNCLAIMED", null, null, DELAY);

        assertThat(result).isFalse();
        verify(dispatches, never()).reschedule(any(), any(), any(), anyString());
        verifyNoInteractions(payouts);
    }

    @Test
    void retryWithoutProviderBatchReschedulesAsPendingAndMarksUnknownForProviderUnknownCode() {
        PayoutDispatchRecord record = record(null);
        when(dispatches.reschedule(record, DELAY, "PROVIDER_UNKNOWN", "PENDING")).thenReturn(true);

        boolean result = service.retry(record, DELAY, "PROVIDER_UNKNOWN");

        assertThat(result).isTrue();
        verify(payouts).markProviderUnknown(payoutId);
    }

    @Test
    void retryWithoutProviderBatchMarksUnknownForDispatchErrorCode() {
        PayoutDispatchRecord record = record(null);
        when(dispatches.reschedule(record, DELAY, "DISPATCH_ERROR", "PENDING")).thenReturn(true);

        assertThat(service.retry(record, DELAY, "DISPATCH_ERROR")).isTrue();
        verify(payouts).markProviderUnknown(payoutId);
    }

    @Test
    void retryWithoutProviderBatchForOtherCodeDoesNotMarkPayoutUnknown() {
        PayoutDispatchRecord record = record(null);
        when(dispatches.reschedule(record, DELAY, "RATE_LIMITED", "PENDING")).thenReturn(true);

        assertThat(service.retry(record, DELAY, "RATE_LIMITED")).isTrue();
        verifyNoInteractions(payouts);
    }

    @Test
    void retryWithProviderBatchWaitsForProviderAndNeverMarksPayoutUnknown() {
        PayoutDispatchRecord record = record("batch-1");
        when(dispatches.reschedule(record, DELAY, "PROVIDER_UNKNOWN", "WAITING_PROVIDER")).thenReturn(true);

        assertThat(service.retry(record, DELAY, "PROVIDER_UNKNOWN")).isTrue();
        verifyNoInteractions(payouts);
    }

    @Test
    void retryReturnsFalseAndSkipsSettlementWhenRescheduleLost() {
        PayoutDispatchRecord record = record(null);
        when(dispatches.reschedule(record, DELAY, "PROVIDER_UNKNOWN", "PENDING")).thenReturn(false);

        assertThat(service.retry(record, DELAY, "PROVIDER_UNKNOWN")).isFalse();
        verifyNoInteractions(payouts);
    }

    @Test
    void acceptedRecordsEvidenceReschedulesWaitingProviderAndMarksPayoutAccepted() {
        PayoutDispatchRecord record = record(null);
        when(dispatches.recordProviderEvidence(record, "batch-1", "PENDING", "item-1", "PENDING", null, null, null))
                .thenReturn(true);
        when(dispatches.reschedule(record, DELAY, null, "WAITING_PROVIDER")).thenReturn(true);

        boolean result = service.accepted(record, "batch-1", "PENDING", "item-1", "PENDING", null, null, DELAY);

        assertThat(result).isTrue();
        InOrder order = inOrder(dispatches, payouts);
        order.verify(dispatches).recordProviderEvidence(record, "batch-1", "PENDING", "item-1", "PENDING", null, null, null);
        order.verify(dispatches).reschedule(record, DELAY, null, "WAITING_PROVIDER");
        order.verify(payouts).markProviderAccepted(payoutId);
    }

    @Test
    void acceptedWithFeeRecordsUsdFeeCurrency() {
        PayoutDispatchRecord record = record(null);
        when(dispatches.recordProviderEvidence(record, "batch-1", "PENDING", "item-1", "PENDING", 9L, "USD", 12L))
                .thenReturn(true);
        when(dispatches.reschedule(record, DELAY, null, "WAITING_PROVIDER")).thenReturn(true);

        assertThat(service.accepted(record, "batch-1", "PENDING", "item-1", "PENDING", 9L, 12L, DELAY)).isTrue();
        verify(payouts).markProviderAccepted(payoutId);
    }

    @Test
    void acceptedDoesNotMarkPayoutAcceptedWhenEvidenceOrRescheduleFails() {
        PayoutDispatchRecord record = record(null);
        when(dispatches.recordProviderEvidence(any(), anyString(), anyString(), anyString(), anyString(), any(), any(), any()))
                .thenReturn(true, false);
        when(dispatches.reschedule(record, DELAY, null, "WAITING_PROVIDER")).thenReturn(false);

        assertThat(service.accepted(record, "batch-1", "PENDING", "item-1", "PENDING", null, null, DELAY)).isFalse();
        assertThat(service.accepted(record, "batch-1", "PENDING", "item-1", "PENDING", null, null, DELAY)).isFalse();

        verify(dispatches).reschedule(record, DELAY, null, "WAITING_PROVIDER");
        verifyNoInteractions(payouts);
    }

    @Test
    void reviewWithEvidenceRecordsEvidenceWithoutFeeThenTransitionsAndMarksNeedsReview() {
        PayoutDispatchRecord record = record("batch-1");
        when(dispatches.recordProviderEvidence(record, "batch-1", "SUCCESS", "item-1", "RETURNED", null, null, null))
                .thenReturn(true);
        when(dispatches.transition(record, "NEEDS_REVIEW", "PROVIDER_STATUS_UNEXPECTED")).thenReturn(true);

        boolean result = service.reviewWithEvidence(record, "batch-1", "SUCCESS", "item-1", "RETURNED",
                "PROVIDER_STATUS_UNEXPECTED");

        assertThat(result).isTrue();
        InOrder order = inOrder(dispatches, payouts);
        order.verify(dispatches).recordProviderEvidence(record, "batch-1", "SUCCESS", "item-1", "RETURNED", null, null, null);
        order.verify(dispatches).transition(record, "NEEDS_REVIEW", "PROVIDER_STATUS_UNEXPECTED");
        order.verify(payouts).markProviderNeedsReview(payoutId, "PROVIDER_STATUS_UNEXPECTED");
    }

    @Test
    void reviewWithEvidenceSkipsTransitionAndSettlementWhenEvidenceWasNotRecorded() {
        PayoutDispatchRecord record = record("batch-1");
        when(dispatches.recordProviderEvidence(any(), anyString(), anyString(), anyString(), anyString(), any(), any(), any()))
                .thenReturn(false);

        boolean result = service.reviewWithEvidence(record, "batch-1", "SUCCESS", "item-1", "RETURNED",
                "PROVIDER_STATUS_UNEXPECTED");

        assertThat(result).isFalse();
        verify(dispatches, never()).transition(any(), anyString(), any());
        verifyNoInteractions(payouts);
    }

    @Test
    void reviewWithEvidenceSkipsSettlementWhenDispatchAlreadyTerminal() {
        PayoutDispatchRecord record = record("batch-1");
        when(dispatches.recordProviderEvidence(any(), anyString(), anyString(), anyString(), anyString(), any(), any(), any()))
                .thenReturn(true);
        when(dispatches.transition(record, "NEEDS_REVIEW", "PROVIDER_STATUS_UNEXPECTED")).thenReturn(false);

        boolean result = service.reviewWithEvidence(record, "batch-1", "SUCCESS", "item-1", "RETURNED",
                "PROVIDER_STATUS_UNEXPECTED");

        assertThat(result).isFalse();
        verifyNoInteractions(payouts);
    }

    private PayoutDispatchRecord record(String providerBatchId) {
        return new PayoutDispatchRecord(payoutId, 1, "v2:active:encrypted", "US", "USD",
                10_000, 730, new BigDecimal("1371.25000000"), "BOK_ECOS", LocalDate.parse("2026-11-21"),
                10_011, 11, null, null, null, "sender-batch", "sender-item", "request-id", "0".repeat(64),
                providerBatchId, null, null, null, "PROCESSING", NOW, NOW, 1, NOW, null, UUID.randomUUID(),
                NOW.plusSeconds(60));
    }
}
