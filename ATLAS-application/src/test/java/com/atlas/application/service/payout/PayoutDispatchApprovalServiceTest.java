package com.atlas.application.service.payout;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.atlas.application.repository.payout.PayoutDispatchRepository;
import com.atlas.application.security.PayoutRecipientCipher;
import com.atlas.payment.provider.exchange.ExchangeRateProvider;
import com.atlas.payment.provider.exchange.ExchangeRateQuote;
import com.atlas.settlement.dto.response.SellerPayoutResponse;
import com.atlas.settlement.exception.SettlementException;
import com.atlas.settlement.dto.response.SettlementPayoutStatus;
import com.atlas.settlement.service.SettlementPayoutService;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Base64;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class PayoutDispatchApprovalServiceTest {
    private static final String KEY = Base64.getEncoder().encodeToString(new byte[32]);
    private final SettlementPayoutService payouts = mock(SettlementPayoutService.class);
    private final SellerPayoutProfileService profiles = mock(SellerPayoutProfileService.class);
    private final ExchangeRateProvider exchangeRates = mock(ExchangeRateProvider.class);
    private final PayoutDispatchRepository dispatches = mock(PayoutDispatchRepository.class);
    private final PayoutDispatchApprovalCommitService committer = mock(PayoutDispatchApprovalCommitService.class);
    private final PayoutRecipientCipher cipher = new PayoutRecipientCipher(KEY);
    private final PayoutDispatchApprovalService service = new PayoutDispatchApprovalService(
            payouts, profiles, exchangeRates, new PayoutFxConversionService(), cipher, dispatches, committer);

    @Test
    void freezesVerifiedRecipientFxAndStableProviderIdentifiersInOutboxPlan() {
        UUID payoutId = UUID.randomUUID();
        UUID sellerId = UUID.randomUUID();
        Instant requestedAt = Instant.parse("2026-11-22T00:00:00Z");
        SellerPayoutResponse requested = new SellerPayoutResponse(payoutId, sellerId, requestedAt,
                requestedAt.plusSeconds(60), 10_000, 0, SettlementPayoutStatus.REQUESTED,
                requestedAt, null, null, null);
        var recipient = new SellerPayoutProfileService.ActiveRecipient(
                sellerId, UUID.randomUUID(), 3, "US", "USD", "seller@example.test");
        when(dispatches.find(payoutId)).thenReturn(Optional.empty());
        when(payouts.find(payoutId)).thenReturn(requested);
        when(profiles.requireActiveRecipient(sellerId)).thenReturn(recipient);
        when(exchangeRates.latestUsdKrwRate()).thenReturn(new ExchangeRateQuote(
                new BigDecimal("1371.25"), "BOK_ECOS", LocalDate.parse("2026-11-21")));
        SellerPayoutResponse approved = new SellerPayoutResponse(payoutId, sellerId, requestedAt,
                requestedAt.plusSeconds(60), 10_000, 0, SettlementPayoutStatus.APPROVED,
                requestedAt, null, null, null);
        when(committer.approve(eq(payoutId), any(PayoutDispatchPlan.class), eq(recipient),
                eq("approval-1"), eq("operator-1"), eq("verified recipient and country"))).thenReturn(approved);

        SellerPayoutResponse result = service.approve(
                payoutId, "approval-1", "operator-1", "verified recipient and country");

        ArgumentCaptor<PayoutDispatchPlan> plan = ArgumentCaptor.forClass(PayoutDispatchPlan.class);
        verify(committer).approve(eq(payoutId), plan.capture(), eq(recipient), eq("approval-1"),
                eq("operator-1"), eq("verified recipient and country"));
        assertThat(result.state()).isEqualTo(SettlementPayoutStatus.APPROVED);
        assertThat(plan.getValue().recipientEmailCiphertext()).startsWith("v2:active:")
                .doesNotContain("seller@example.test");
        assertThat(cipher.decrypt(plan.getValue().recipientEmailCiphertext())).isEqualTo("seller@example.test");
        assertThat(plan.getValue().providerAmountMinor()).isEqualTo(730);
        assertThat(plan.getValue().providerDebitBookValueWon()).isEqualTo(10_011);
        assertThat(plan.getValue().payoutFxRoundingExpenseWon()).isEqualTo(11);
        assertThat(plan.getValue().senderBatchId()).isEqualTo(UUID.nameUUIDFromBytes(
                ("atlas-paypal-payout:batch:" + payoutId).getBytes(StandardCharsets.UTF_8)).toString());
        assertThat(plan.getValue().requestPayloadSha256()).hasSize(64);
    }

    @Test
    void refusesPayoutWhoseCentRoundingWouldExceedThePlatformCostLimit() {
        UUID payoutId = UUID.randomUUID();
        UUID sellerId = UUID.randomUUID();
        Instant now = Instant.parse("2026-11-22T00:00:00Z");
        when(dispatches.find(payoutId)).thenReturn(Optional.empty());
        when(payouts.find(payoutId)).thenReturn(new SellerPayoutResponse(payoutId, sellerId, now,
                now.plusSeconds(60), 1, 0, SettlementPayoutStatus.REQUESTED, now, null, null, null));
        when(profiles.requireActiveRecipient(sellerId)).thenReturn(new SellerPayoutProfileService.ActiveRecipient(
                sellerId, UUID.randomUUID(), 1, "US", "USD", "seller@example.test"));
        when(exchangeRates.latestUsdKrwRate()).thenReturn(new ExchangeRateQuote(
                new BigDecimal("1371.25"), "BOK_ECOS", LocalDate.parse("2026-11-21")));

        assertThatThrownBy(() -> service.approve(payoutId, "approval-2", "operator-1", "manual small amount"))
                .isInstanceOf(SettlementException.class)
                .hasMessageContaining("not eligible");
        verify(committer, never()).approve(any(), any(), any(), anyString(), anyString(), anyString());
    }
}
