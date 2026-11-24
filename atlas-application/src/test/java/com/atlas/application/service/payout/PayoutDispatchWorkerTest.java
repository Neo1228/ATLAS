package com.atlas.application.service.payout;

import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.atlas.application.security.PayoutRecipientCipher;
import com.atlas.payment.dto.response.PayPalPayoutBatchResponse;
import com.atlas.payment.provider.paypal.payouts.PayPalPayoutException;
import com.atlas.payment.provider.paypal.payouts.PayPalPayoutsProvider;
import com.atlas.settlement.model.PayoutDispatchMetrics;
import com.atlas.settlement.model.PayoutDispatchRecord;
import com.atlas.settlement.service.PayoutDispatchStore;
import com.atlas.settlement.service.SettlementPayoutService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class PayoutDispatchWorkerTest {
    private static final Instant NOW = Instant.parse("2026-11-22T00:00:00Z");
    private static final String KEY = Base64.getEncoder().encodeToString(new byte[32]);
    private final PayoutDispatchStore repository = mock(PayoutDispatchStore.class);
    private final PayoutDispatchOutcomeService outcomes = mock(PayoutDispatchOutcomeService.class);
    private final SettlementPayoutService payouts = mock(SettlementPayoutService.class);
    private final PayPalPayoutsProvider provider = mock(PayPalPayoutsProvider.class);
    private final PayoutRecipientCipher cipher = new PayoutRecipientCipher(KEY);
    private final PayoutDispatchWorker worker = new PayoutDispatchWorker(repository, outcomes, payouts, provider,
            cipher, new PayoutFxConversionService(), Clock.fixed(NOW, ZoneOffset.UTC), new SimpleMeterRegistry(),
            1, Duration.ofSeconds(60), 8, Duration.ofDays(29), Duration.ofDays(60));
    private final UUID payoutId = UUID.randomUUID();

    @Test
    void createsBatchOnlyAfterFenceAndUsesEncryptedRecipientSnapshot() {
        PayoutDispatchRecord record = leasedRecord();
        when(repository.claim(1, Duration.ofSeconds(60))).thenReturn(List.of(record));
        when(repository.metricsSnapshot()).thenReturn(new PayoutDispatchMetrics(1, 0, 0, 0, 0, 0));
        when(payouts.beginDispatch(payoutId)).thenReturn(true);
        when(provider.createBatch(any())).thenReturn(batch("PENDING", List.of()));
        when(outcomes.accepted(eq(record), eq("provider-batch"), eq("PENDING"), isNull(), isNull(),
                isNull(), isNull(), any(Duration.class))).thenReturn(true);

        worker.dispatchDue();

        verify(provider).createBatch(argThat(request ->
                "seller@example.test".equals(request.recipientEmail())
                        && request.senderBatchId().equals("sender-batch")
                        && request.senderItemId().equals("sender-item")
                        && request.requestId().equals("request-id")));
        verify(outcomes).accepted(eq(record), eq("provider-batch"), eq("PENDING"), isNull(), isNull(),
                isNull(), isNull(), any(Duration.class));
    }

    @Test
    void cancelledPayoutNeverReachesProvider() {
        PayoutDispatchRecord record = leasedRecord();
        when(repository.claim(1, Duration.ofSeconds(60))).thenReturn(List.of(record));
        when(repository.metricsSnapshot()).thenReturn(new PayoutDispatchMetrics(0, 0, 0, 0, 0, 0));
        when(payouts.beginDispatch(payoutId)).thenReturn(false);

        worker.dispatchDue();

        verify(provider, never()).createBatch(any());
        verify(outcomes).cancel(record);
    }

    @Test
    void duplicateBatchWithOriginalIdWaitsAndPollsTheExistingBatch() {
        PayoutDispatchRecord record = leasedRecord();
        when(repository.claim(1, Duration.ofSeconds(60))).thenReturn(List.of(record));
        when(repository.metricsSnapshot()).thenReturn(new PayoutDispatchMetrics(0, 0, 0, 0, 0, 0));
        when(payouts.beginDispatch(payoutId)).thenReturn(true);
        when(provider.createBatch(any())).thenThrow(new PayPalPayoutException(
                "duplicate", null, PayPalPayoutException.Classification.DUPLICATE_BATCH, "sender-batch", "ORIGINAL-1"));

        worker.dispatchDue();

        verify(outcomes).accepted(eq(record), eq("ORIGINAL-1"), eq("UNKNOWN"), isNull(), isNull(), isNull(), isNull(),
                any(Duration.class));
        verify(outcomes, never()).review(any(), any());
        verify(outcomes, never()).fail(any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void duplicateBatchWithoutOriginalIdEscalatesToReviewInsteadOfFailing() {
        PayoutDispatchRecord record = leasedRecord();
        when(repository.claim(1, Duration.ofSeconds(60))).thenReturn(List.of(record));
        when(repository.metricsSnapshot()).thenReturn(new PayoutDispatchMetrics(0, 0, 0, 0, 0, 0));
        when(payouts.beginDispatch(payoutId)).thenReturn(true);
        when(provider.createBatch(any())).thenThrow(new PayPalPayoutException(
                "duplicate", null, PayPalPayoutException.Classification.DUPLICATE_BATCH, "sender-batch", null));

        worker.dispatchDue();

        verify(outcomes).review(eq(record), eq("DUPLICATE_WITHOUT_BATCH_ID"));
        verify(outcomes, never()).accepted(any(), any(), any(), any(), any(), any(), any(), any());
        verify(outcomes, never()).fail(any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void batchSuccessDoesNotOverrideUnclaimedItemStatus() {
        PayoutDispatchRecord record = leasedRecord();
        PayPalPayoutBatchResponse.Item item = item("UNCLAIMED", null, null);
        when(repository.claim(1, Duration.ofSeconds(60))).thenReturn(List.of(record));
        when(repository.metricsSnapshot()).thenReturn(new PayoutDispatchMetrics(0, 0, 0, 0, 0, 0));
        when(payouts.beginDispatch(payoutId)).thenReturn(true);
        when(provider.createBatch(any())).thenReturn(batch("SUCCESS", List.of(item)));

        worker.dispatchDue();

        verify(outcomes).unclaimed(eq(record), eq("provider-batch"), eq("SUCCESS"), eq("provider-item"),
                eq("UNCLAIMED"), isNull(), isNull(), any(Duration.class));
        verify(outcomes, never()).complete(any(), any(), any(), any(), any(), anyLong(), anyLong());
    }

    @Test
    void successfulItemPostsFeeUsingTheStoredFxRate() {
        PayoutDispatchRecord record = leasedRecord();
        PayPalPayoutBatchResponse.Item item = item("SUCCESS", new BigDecimal("0.25"), "USD");
        when(repository.claim(1, Duration.ofSeconds(60))).thenReturn(List.of(record));
        when(repository.metricsSnapshot()).thenReturn(new PayoutDispatchMetrics(0, 0, 0, 0, 0, 0));
        when(payouts.beginDispatch(payoutId)).thenReturn(true);
        when(provider.createBatch(any())).thenReturn(batch("SUCCESS", List.of(item)));
        when(outcomes.complete(eq(record), eq("provider-batch"), eq("SUCCESS"), eq("provider-item"),
                eq("SUCCESS"), eq(25L), eq(343L))).thenReturn(true);

        worker.dispatchDue();

        verify(outcomes).complete(record, "provider-batch", "SUCCESS", "provider-item", "SUCCESS", 25, 343);
        verify(outcomes, never()).fail(any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void successfulItemWithNonUsdRecipientConversionIsHeldForReview() {
        PayoutDispatchRecord record = leasedRecord();
        PayPalPayoutBatchResponse.Item item = new PayPalPayoutBatchResponse.Item("provider-item", "sender-item", "SUCCESS",
                new BigDecimal("7.30"), "USD", new BigDecimal("0.25"), "USD",
                new BigDecimal("7.30"), "USD", new BigDecimal("10.00"), "CAD", new BigDecimal("1.3698"), true);
        when(repository.claim(1, Duration.ofSeconds(60))).thenReturn(List.of(record));
        when(repository.metricsSnapshot()).thenReturn(new PayoutDispatchMetrics(0, 0, 0, 0, 0, 0));
        when(payouts.beginDispatch(payoutId)).thenReturn(true);
        when(provider.createBatch(any())).thenReturn(batch("SUCCESS", List.of(item)));

        worker.dispatchDue();

        verify(outcomes).reviewWithEvidence(record, "provider-batch", "SUCCESS", "provider-item",
                "SUCCESS", "RECIPIENT_CURRENCY_CONVERSION");
        verify(outcomes, never()).complete(any(), any(), any(), any(), any(), anyLong(), anyLong());
    }

    @Test
    void successfulItemWithEmptyRecipientConversionObjectIsHeldForReview() {
        PayoutDispatchRecord record = leasedRecord();
        PayPalPayoutBatchResponse.Item item = new PayPalPayoutBatchResponse.Item("provider-item", "sender-item", "SUCCESS",
                new BigDecimal("7.30"), "USD", new BigDecimal("0.25"), "USD",
                null, null, null, null, null, true);
        when(repository.claim(1, Duration.ofSeconds(60))).thenReturn(List.of(record));
        when(repository.metricsSnapshot()).thenReturn(new PayoutDispatchMetrics(0, 0, 0, 0, 0, 0));
        when(payouts.beginDispatch(payoutId)).thenReturn(true);
        when(provider.createBatch(any())).thenReturn(batch("SUCCESS", List.of(item)));

        worker.dispatchDue();

        verify(outcomes).reviewWithEvidence(record, "provider-batch", "SUCCESS", "provider-item",
                "SUCCESS", "RECIPIENT_CURRENCY_CONVERSION");
        verify(outcomes, never()).complete(any(), any(), any(), any(), any(), anyLong(), anyLong());
    }

    @Test
    void returnedItemMovesToReviewInsteadOfReleasingTheReservation() {
        PayoutDispatchRecord record = leasedRecord();
        PayPalPayoutBatchResponse.Item item = item("RETURNED", new BigDecimal("0.25"), "USD");
        when(repository.claim(1, Duration.ofSeconds(60))).thenReturn(List.of(record));
        when(repository.metricsSnapshot()).thenReturn(new PayoutDispatchMetrics(0, 0, 0, 0, 0, 0));
        when(payouts.beginDispatch(payoutId)).thenReturn(true);
        when(provider.createBatch(any())).thenReturn(batch("SUCCESS", List.of(item)));

        worker.dispatchDue();

        verify(outcomes).reviewWithEvidence(record, "provider-batch", "SUCCESS", "provider-item",
                "RETURNED", "UNKNOWN_PROVIDER_STATUS");
        verify(outcomes, never()).fail(any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void failedItemWithNonzeroProviderFeeRequiresReviewBeforeReleasingPayout() {
        PayoutDispatchRecord record = leasedRecord();
        PayPalPayoutBatchResponse.Item item = item("FAILED", new BigDecimal("0.25"), "USD");
        when(repository.claim(1, Duration.ofSeconds(60))).thenReturn(List.of(record));
        when(repository.metricsSnapshot()).thenReturn(new PayoutDispatchMetrics(0, 0, 0, 0, 0, 0));
        when(payouts.beginDispatch(payoutId)).thenReturn(true);
        when(provider.createBatch(any())).thenReturn(batch("FAILED", List.of(item)));

        worker.dispatchDue();

        verify(outcomes).reviewWithEvidence(record, "provider-batch", "FAILED", "provider-item",
                "FAILED", "FAILED_FEE_UNCONFIRMED");
        verify(outcomes, never()).fail(any(), any(), any(), any(), any(), any(), any(), any());
    }

    private PayoutDispatchRecord leasedRecord() {
        return new PayoutDispatchRecord(payoutId, 1, cipher.encrypt("seller@example.test"), "US", "USD",
                10_000, 730, new BigDecimal("1371.25000000"), "BOK_ECOS", LocalDate.parse("2026-11-21"),
                10_011, 11, null, null, null, "sender-batch", "sender-item", "request-id", "0".repeat(64),
                null, null, null, null, "LEASED", NOW, NOW, 1, NOW, null, UUID.randomUUID(), NOW.plusSeconds(60));
    }

    private static PayPalPayoutBatchResponse batch(String batchStatus, List<PayPalPayoutBatchResponse.Item> items) {
        return new PayPalPayoutBatchResponse("provider-batch", "sender-batch", batchStatus,
                new BigDecimal("7.30"), "USD", null, null, items);
    }

    private static PayPalPayoutBatchResponse.Item item(String status, BigDecimal fee, String feeCurrency) {
        return new PayPalPayoutBatchResponse.Item("provider-item", "sender-item", status,
                new BigDecimal("7.30"), "USD", fee, feeCurrency, null, null, null, null, null, false);
    }
}
