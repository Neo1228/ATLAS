package com.atlas.application.service.payout;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.atlas.application.controller.dto.PayoutProviderResolutionRequest;
import com.atlas.settlement.service.PayoutDispatchRecord;
import com.atlas.settlement.service.PayoutDispatchStore;
import com.atlas.settlement.service.PayoutOperatorActionStore;
import com.atlas.settlement.dto.response.SellerPayoutResponse;
import com.atlas.settlement.exception.SettlementException;
import com.atlas.settlement.dto.response.SettlementPayoutStatus;
import com.atlas.settlement.service.SettlementPayoutService;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class PayoutManualOperatorServiceTest {
    private static final Instant NOW = Instant.parse("2026-11-22T00:00:00Z");
    private final SettlementPayoutService payouts = mock(SettlementPayoutService.class);
    private final PayoutDispatchStore dispatches = mock(PayoutDispatchStore.class);
    private final PayoutOperatorActionStore actions = mock(PayoutOperatorActionStore.class);
    private final PayoutManualOperatorService service = new PayoutManualOperatorService(
            payouts, dispatches, actions, new PayoutFxConversionService(), Clock.fixed(NOW, ZoneOffset.UTC));
    private final UUID payoutId = UUID.randomUUID();
    private final UUID sellerId = UUID.randomUUID();

    @Test
    void manuallyConfirmedProviderSuccessUsesStoredFxAndRequiresExactAmount() {
        PayoutDispatchRecord record = needsReviewRecord();
        SellerPayoutResponse needsReview = response(SettlementPayoutStatus.NEEDS_REVIEW);
        SellerPayoutResponse confirmed = response(SettlementPayoutStatus.CONFIRMED);
        when(dispatches.find(payoutId)).thenReturn(Optional.of(record));
        when(payouts.find(payoutId)).thenReturn(needsReview);
        when(payouts.confirmProviderSuccess(payoutId, 10_011, 11, 343)).thenReturn(confirmed);
        when(dispatches.resolveNeedsReview(payoutId, "COMPLETED", "provider-batch", "provider-item",
                25L, 343L, "OPERATOR_PROVIDER_PAID")).thenReturn(true);
        when(actions.record(payoutId, "review-1", "PROVIDER_REVIEW_PAID", "operator-1",
                "case-2026-001", NOW)).thenReturn(true);

        SellerPayoutResponse result = service.resolveProviderReview(payoutId, "review-1", "operator-1",
                new PayoutProviderResolutionRequest("PAID", "case-2026-001", "provider-batch",
                        "provider-item", 730L, "USD", 25L));

        assertThat(result.state()).isEqualTo(SettlementPayoutStatus.CONFIRMED);
        verify(payouts).confirmProviderSuccess(payoutId, 10_011, 11, 343);
        verify(actions).record(payoutId, "review-1", "PROVIDER_REVIEW_PAID", "operator-1",
                "case-2026-001", NOW);
    }

    @Test
    void rejectsManualProviderEvidenceThatChangesTheFrozenUsdAmount() {
        when(dispatches.find(payoutId)).thenReturn(Optional.of(needsReviewRecord()));
        when(payouts.find(payoutId)).thenReturn(response(SettlementPayoutStatus.NEEDS_REVIEW));

        assertThatThrownBy(() -> service.resolveProviderReview(payoutId, "review-2", "operator-1",
                new PayoutProviderResolutionRequest("PAID", "case-2026-002", "provider-batch",
                        "provider-item", 731L, "USD", 25L)))
                .isInstanceOf(SettlementException.class);
        verify(payouts, never()).confirmProviderSuccess(any(), anyLong(), anyLong(), anyLong());
    }


    @Test
    void confirmedNoTransferReleasesOnlyTheReviewedPayoutReservation() {
        PayoutDispatchRecord record = needsReviewRecord();
        when(dispatches.find(payoutId)).thenReturn(Optional.of(record));
        when(payouts.find(payoutId)).thenReturn(response(SettlementPayoutStatus.NEEDS_REVIEW));
        when(payouts.failWithProviderEvidence(payoutId, "OPERATOR_CONFIRMED_NO_TRANSFER"))
                .thenReturn(response(SettlementPayoutStatus.FAILED));
        when(dispatches.resolveNeedsReview(payoutId, "FAILED", null, null, 0L, 0L, "OPERATOR_NO_TRANSFER"))
                .thenReturn(true);
        when(actions.record(payoutId, "review-no-transfer", "PROVIDER_REVIEW_NOT_PAID", "operator-1",
                "paypal-case-2026-01", NOW)).thenReturn(true);

        SellerPayoutResponse failed = service.resolveProviderReview(payoutId, "review-no-transfer",
                "operator-1", new PayoutProviderResolutionRequest(
                        "NOT_PAID", "paypal-case-2026-01", null, null, null, "USD", 0L));

        assertThat(failed.state()).isEqualTo(SettlementPayoutStatus.FAILED);
        verify(payouts).failWithProviderEvidence(payoutId, "OPERATOR_CONFIRMED_NO_TRANSFER");
        verify(actions).record(payoutId, "review-no-transfer", "PROVIDER_REVIEW_NOT_PAID", "operator-1",
                "paypal-case-2026-01", NOW);
    }
    private PayoutDispatchRecord needsReviewRecord() {
        return new PayoutDispatchRecord(payoutId, 1, "v2:active:encrypted", "US", "USD",
                10_000, 730, new BigDecimal("1371.25000000"), "BOK_ECOS", LocalDate.parse("2026-11-21"),
                10_011, 11, null, null, null, "sender-batch", "sender-item", "request-id", "0".repeat(64),
                null, null, null, null, "NEEDS_REVIEW", NOW, NOW, 8, NOW, "MAX_ATTEMPTS_OR_AGE", null, null);
    }

    private SellerPayoutResponse response(SettlementPayoutStatus state) {
        return new SellerPayoutResponse(payoutId, sellerId, NOW, NOW.plusSeconds(60), 10_000, 0,
                state, NOW, null, null, null);
    }
}
