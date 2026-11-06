package com.atlas.settlement.service.impl;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.atlas.settlement.dto.request.CapturePostingRequest;
import com.atlas.settlement.dto.request.CheckoutSettlementPostingRequest;
import com.atlas.settlement.dto.request.SellerOrderAllocationRequest;
import com.atlas.settlement.repository.SettlementJournalRepository;
import com.atlas.settlement.persistence.projection.CaptureJournalRecord;
import com.atlas.settlement.persistence.projection.CheckoutSettlementAllocation;
import com.atlas.settlement.persistence.projection.CheckoutSettlementRecord;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class SettlementServiceImplTest {
    private final SettlementJournalRepository journals = mock(SettlementJournalRepository.class);
    private final Instant now = Instant.parse("2026-11-04T00:00:00Z");
    private final SettlementServiceImpl service = new SettlementServiceImpl(
            journals, Clock.fixed(now, ZoneOffset.UTC));
    private final UUID orderId = UUID.randomUUID();
    private final UUID attemptId = UUID.randomUUID();
    private final UUID sellerId = UUID.randomUUID();
    private final CapturePostingRequest request = new CapturePostingRequest(orderId, attemptId, sellerId, 2_500);
    private final CaptureJournalRecord captureFacts =
            new CaptureJournalRecord(orderId, attemptId, sellerId, 2_500);

    @Test
    void postsTwoEntriesOnceForNewCapture() {
        UUID journalId = UUID.randomUUID();
        when(journals.insertIfAbsent(captureFacts, now)).thenReturn(Optional.of(journalId));

        service.recordCapture(request);

        verify(journals).insertEntries(journalId, 2_500, now);
        verify(journals, never()).findByAttemptId(attemptId);
    }

    @Test
    void treatsIdenticalAttemptReplayAsIdempotent() {
        when(journals.insertIfAbsent(captureFacts, now)).thenReturn(Optional.empty());
        when(journals.findByAttemptId(attemptId)).thenReturn(Optional.of(captureFacts));

        service.recordCapture(request);

        verify(journals, never()).insertEntries(any(UUID.class), anyLong(), any(Instant.class));
    }

    @Test
    void rejectsReplayWithDifferentCaptureFacts() {
        when(journals.insertIfAbsent(captureFacts, now)).thenReturn(Optional.empty());
        when(journals.findByAttemptId(attemptId)).thenReturn(Optional.of(
                new CaptureJournalRecord(orderId, attemptId, sellerId, 2_400)));

        assertThrows(IllegalStateException.class, () -> service.recordCapture(request));
    }

    @Test
    void rejectsInvalidCaptureFactsBeforePersistence() {
        assertThrows(IllegalArgumentException.class,
                () -> service.recordCapture(new CapturePostingRequest(orderId, null, sellerId, 2_500)));

        verify(journals, never()).insertIfAbsent(any(CaptureJournalRecord.class), any(Instant.class));
    }

    @Test
    void normalizesCheckoutAllocationsIntoDeterministicOrder() {
        UUID checkoutId = UUID.randomUUID();
        UUID attemptId = UUID.randomUUID();
        UUID firstOrder = UUID.randomUUID();
        UUID secondOrder = UUID.randomUUID();
        SellerOrderAllocationRequest first = new SellerOrderAllocationRequest(firstOrder, UUID.randomUUID(), 60);
        SellerOrderAllocationRequest second = new SellerOrderAllocationRequest(secondOrder, UUID.randomUUID(), 40);

        CheckoutSettlementRecord normalized = SettlementServiceImpl.validateAndNormalizeCheckoutCapture(
                new CheckoutSettlementPostingRequest(checkoutId, attemptId, 100, List.of(second, first)));

        List<CheckoutSettlementAllocation> expected = (firstOrder.compareTo(secondOrder) < 0
                ? List.of(first, second) : List.of(second, first)).stream()
                .map(SettlementServiceImplTest::stored).toList();
        assertEquals(expected, normalized.allocations());
        assertEquals(attemptId, normalized.attemptId());
        assertEquals(100, normalized.allocations().stream().mapToLong(CheckoutSettlementAllocation::amountWon).sum());
    }

    @Test
    void rejectsInvalidCheckoutFactsAndDuplicateOrders() {
        UUID checkoutId = UUID.randomUUID();
        UUID attemptId = UUID.randomUUID();
        UUID orderId = UUID.randomUUID();
        UUID sellerId = UUID.randomUUID();
        assertThrows(IllegalArgumentException.class, () -> SettlementServiceImpl.validateAndNormalizeCheckoutCapture(
                new CheckoutSettlementPostingRequest(checkoutId, null, 1,
                        List.of(new SellerOrderAllocationRequest(orderId, sellerId, 1)))));
        assertThrows(IllegalArgumentException.class, () -> SettlementServiceImpl.validateAndNormalizeCheckoutCapture(
                new CheckoutSettlementPostingRequest(checkoutId, attemptId, 0,
                        List.of(new SellerOrderAllocationRequest(orderId, sellerId, 1)))));
        assertThrows(IllegalArgumentException.class, () -> SettlementServiceImpl.validateAndNormalizeCheckoutCapture(
                new CheckoutSettlementPostingRequest(checkoutId, attemptId, 2,
                        List.of(new SellerOrderAllocationRequest(orderId, sellerId, 1)))));
        assertThrows(IllegalArgumentException.class, () -> SettlementServiceImpl.validateAndNormalizeCheckoutCapture(
                new CheckoutSettlementPostingRequest(checkoutId, attemptId, 1,
                        List.of(new SellerOrderAllocationRequest(orderId, sellerId, 0)))));
        assertThrows(IllegalArgumentException.class, () -> SettlementServiceImpl.validateAndNormalizeCheckoutCapture(
                new CheckoutSettlementPostingRequest(checkoutId, attemptId, 2, List.of(
                        new SellerOrderAllocationRequest(orderId, sellerId, 1),
                        new SellerOrderAllocationRequest(orderId, UUID.randomUUID(), 1)))));
    }

    @Test
    void recordsCheckoutEntriesAndAllocationsOnceIncludingAttemptId() {
        UUID checkoutId = UUID.randomUUID();
        UUID attemptId = UUID.randomUUID();
        UUID orderId = UUID.randomUUID();
        UUID sellerId = UUID.randomUUID();
        UUID journalId = UUID.randomUUID();
        Instant recordedAt = Instant.parse("2025-01-02T03:04:05Z");
        SettlementServiceImpl checkoutService = new SettlementServiceImpl(journals, Clock.fixed(recordedAt, ZoneOffset.UTC));
        SellerOrderAllocationRequest allocation = new SellerOrderAllocationRequest(orderId, sellerId, 125);
        CheckoutSettlementRecord facts = new CheckoutSettlementRecord(checkoutId, attemptId, 125,
                List.of(stored(allocation)));
        when(journals.insertCheckoutIfAbsent(facts, recordedAt)).thenReturn(Optional.of(journalId));

        checkoutService.recordCheckoutCapture(new CheckoutSettlementPostingRequest(
                checkoutId, attemptId, 125, List.of(allocation)));

        verify(journals).insertCheckoutIfAbsent(facts, recordedAt);
        verify(journals).insertEntries(journalId, 125, recordedAt);
        verify(journals).insertCheckoutAllocations(journalId, List.of(stored(allocation)));
        verifyNoMoreInteractions(journals);
    }

    @Test
    void acceptsIdenticalCheckoutReplayWithoutInsertingAgain() {
        UUID checkoutId = UUID.randomUUID();
        UUID attemptId = UUID.randomUUID();
        UUID orderId = UUID.randomUUID();
        UUID sellerId = UUID.randomUUID();
        Instant recordedAt = Instant.parse("2025-01-02T03:04:05Z");
        SettlementServiceImpl checkoutService = new SettlementServiceImpl(journals, Clock.fixed(recordedAt, ZoneOffset.UTC));
        SellerOrderAllocationRequest allocation = new SellerOrderAllocationRequest(orderId, sellerId, 125);
        CheckoutSettlementRecord facts = new CheckoutSettlementRecord(checkoutId, attemptId, 125,
                List.of(stored(allocation)));
        when(journals.insertCheckoutIfAbsent(facts, recordedAt)).thenReturn(Optional.empty());
        when(journals.findCheckoutById(checkoutId)).thenReturn(Optional.of(facts));

        assertDoesNotThrow(() -> checkoutService.recordCheckoutCapture(
                new CheckoutSettlementPostingRequest(checkoutId, attemptId, 125, List.of(allocation))));

        verify(journals).insertCheckoutIfAbsent(facts, recordedAt);
        verify(journals).findCheckoutById(checkoutId);
        verify(journals, never()).insertEntries(any(UUID.class), anyLong(), any(Instant.class));
        verify(journals, never()).insertCheckoutAllocations(any(UUID.class), anyList());
        verifyNoMoreInteractions(journals);
    }

    @Test
    void rejectsCheckoutReplayWithChangedAttemptSellerAmountOrAllocationFacts() {
        UUID checkoutId = UUID.randomUUID();
        UUID attemptId = UUID.randomUUID();
        UUID orderId = UUID.randomUUID();
        UUID originalSellerId = UUID.randomUUID();
        UUID changedSellerId = UUID.randomUUID();
        Instant recordedAt = Instant.parse("2025-01-02T03:04:05Z");
        SettlementServiceImpl checkoutService = new SettlementServiceImpl(journals, Clock.fixed(recordedAt, ZoneOffset.UTC));
        SellerOrderAllocationRequest originalAllocation = new SellerOrderAllocationRequest(orderId, originalSellerId, 125);
        CheckoutSettlementRecord originalFacts = new CheckoutSettlementRecord(checkoutId, attemptId, 125,
                List.of(stored(originalAllocation)));
        when(journals.insertCheckoutIfAbsent(any(CheckoutSettlementRecord.class), eq(recordedAt)))
                .thenReturn(Optional.empty());
        when(journals.findCheckoutById(checkoutId)).thenReturn(Optional.of(originalFacts));

        assertThrows(IllegalStateException.class, () -> checkoutService.recordCheckoutCapture(
                new CheckoutSettlementPostingRequest(checkoutId, UUID.randomUUID(), 125,
                        List.of(originalAllocation))));
        assertThrows(IllegalStateException.class, () -> checkoutService.recordCheckoutCapture(
                new CheckoutSettlementPostingRequest(checkoutId, attemptId, 125,
                        List.of(new SellerOrderAllocationRequest(orderId, changedSellerId, 125)))));
        assertThrows(IllegalStateException.class, () -> checkoutService.recordCheckoutCapture(
                new CheckoutSettlementPostingRequest(checkoutId, attemptId, 126,
                        List.of(new SellerOrderAllocationRequest(orderId, originalSellerId, 126)))));

        verify(journals, times(3)).insertCheckoutIfAbsent(any(CheckoutSettlementRecord.class), eq(recordedAt));
        verify(journals, times(3)).findCheckoutById(checkoutId);
        verify(journals, never()).insertEntries(any(UUID.class), anyLong(), any(Instant.class));
        verify(journals, never()).insertCheckoutAllocations(any(UUID.class), anyList());
    }

    private static CheckoutSettlementAllocation stored(SellerOrderAllocationRequest allocation) {
        return new CheckoutSettlementAllocation(allocation.orderId(), allocation.sellerId(), allocation.amountWon());
    }
}
