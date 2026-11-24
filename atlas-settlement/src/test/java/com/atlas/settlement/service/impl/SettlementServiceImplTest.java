package com.atlas.settlement.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
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
import com.atlas.settlement.dto.request.RefundPostingRequest;
import com.atlas.settlement.dto.request.SellerOrderAllocationRequest;
import com.atlas.settlement.dto.request.SellerSettlementStatementRequest;
import com.atlas.settlement.exception.SettlementException;
import com.atlas.settlement.persistence.projection.CaptureJournalRecord;
import com.atlas.settlement.persistence.projection.CheckoutSettlementAllocation;
import com.atlas.settlement.persistence.projection.CheckoutSettlementRecord;
import com.atlas.settlement.dto.response.SellerSettlementStatementResponse;
import com.atlas.settlement.persistence.projection.RefundJournalRecord;
import com.atlas.settlement.persistence.projection.RefundReservation;
import com.atlas.settlement.persistence.projection.SellerBalance;
import com.atlas.settlement.persistence.projection.SellerStatementRow;
import com.atlas.settlement.persistence.projection.SellerStatementTotals;
import com.atlas.settlement.repository.SettlementJournalRepository;
import com.atlas.settlement.service.SettlementPolicy;
import com.atlas.settlement.repository.SettlementPayoutRepository;
import com.atlas.settlement.repository.SettlementRefundReservationRepository;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class SettlementServiceImplTest {
    private final SettlementJournalRepository journals = mock(SettlementJournalRepository.class);
    private final SettlementPayoutRepository payouts = mock(SettlementPayoutRepository.class);
    private final SettlementRefundReservationRepository refundReservations =
            mock(SettlementRefundReservationRepository.class);
    // 이 단위 테스트는 분개 형태만 검증하므로 요율 0(기존 두 계정 구조)을 쓴다.
    private final SettlementPolicy policy = new SettlementPolicy(0, 0);
    private final Instant now = Instant.parse("2026-11-04T00:00:00Z");
    private final SettlementServiceImpl service = new SettlementServiceImpl(
            journals, payouts, refundReservations, policy, Clock.fixed(now, ZoneOffset.UTC));
    private final UUID orderId = UUID.randomUUID();
    private final UUID attemptId = UUID.randomUUID();
    private final UUID sellerId = UUID.randomUUID();
    private final CapturePostingRequest request = new CapturePostingRequest(orderId, attemptId, sellerId, 2_500);
    private final CaptureJournalRecord captureFacts =
            new CaptureJournalRecord(orderId, attemptId, sellerId, 2_500);

    @Test
    void postsTwoEntriesOnceForNewCapture() {
        UUID journalId = UUID.randomUUID();
        // 요율 0 이면 split 은 fee/reserve 0 이고 seller 몫이 gross 전액이다.
        when(journals.insertIfAbsent(captureFacts, 0, 0, 0, 0, now))
                .thenReturn(Optional.of(journalId));

        service.recordCapture(request);

        verify(journals).insertEntries(journalId, sellerId, 2_500, 0, 0, now);
        verify(journals, never()).findByAttemptId(attemptId);
    }

    @Test
    void treatsIdenticalAttemptReplayAsIdempotent() {
        when(journals.insertIfAbsent(captureFacts, 0, 0, 0, 0, now)).thenReturn(Optional.empty());
        when(journals.findByAttemptId(attemptId)).thenReturn(Optional.of(captureFacts));

        service.recordCapture(request);

        verify(journals, never()).insertEntries(any(UUID.class), any(UUID.class), anyLong(),
                anyLong(), anyLong(), any(Instant.class));
    }

    @Test
    void rejectsReplayWithDifferentCaptureFacts() {
        when(journals.insertIfAbsent(captureFacts, 0, 0, 0, 0, now)).thenReturn(Optional.empty());
        when(journals.findByAttemptId(attemptId)).thenReturn(Optional.of(
                new CaptureJournalRecord(orderId, attemptId, sellerId, 2_400)));

        assertSettlementCode(SettlementException.Code.CONFLICT, () -> service.recordCapture(request));
    }

    @Test
    void rejectsInvalidCaptureFactsBeforePersistence() {
        assertSettlementCode(SettlementException.Code.INVALID_REQUEST,
                () -> service.recordCapture(new CapturePostingRequest(orderId, null, sellerId, 2_500)));

        verify(journals, never()).insertIfAbsent(any(CaptureJournalRecord.class), anyLong(), anyLong(),
                anyInt(), anyInt(), any(Instant.class));
    }

    @Test
    void rejectsInvalidRefundFactsBeforePersistence() {
        assertSettlementCode(SettlementException.Code.INVALID_REQUEST,
                () -> service.recordRefund(new RefundPostingRequest(orderId, null, sellerId, 2_500)));
        verify(journals, never()).insertRefundIfAbsent(any(RefundJournalRecord.class), any(Instant.class));
    }

    @Test
    void rejectsRefundReplayWithChangedFacts() {
        UUID refundId = UUID.randomUUID();
        RefundPostingRequest request = new RefundPostingRequest(orderId, refundId, sellerId, 2_500);
        RefundJournalRecord original = new RefundJournalRecord(orderId, refundId, sellerId, 2_500);
        when(refundReservations.find(refundId)).thenReturn(Optional.of(
                new RefundReservation(
                        refundId, orderId, sellerId, 2_500, "RESERVED", now, now)));
        when(journals.insertRefundIfAbsent(original, now)).thenReturn(Optional.empty());
        when(journals.findByRefundId(refundId)).thenReturn(Optional.of(
                new RefundJournalRecord(orderId, refundId, sellerId, 2_400)));

        assertSettlementCode(SettlementException.Code.CONFLICT, () -> service.recordRefund(request));
    }

    @Test
    void rejectsRefundPostingWithoutAReservation() {
        UUID refundId = UUID.randomUUID();
        RefundPostingRequest request = new RefundPostingRequest(orderId, refundId, sellerId, 2_500);
        when(refundReservations.find(refundId)).thenReturn(Optional.empty());

        assertSettlementCode(SettlementException.Code.CONFLICT, () -> service.recordRefund(request));
        verify(journals, never()).insertRefundIfAbsent(any(RefundJournalRecord.class), any(Instant.class));
    }

    @Test
    void rejectsRefundPostingWhenReservationFactsDiffer() {
        UUID refundId = UUID.randomUUID();
        RefundPostingRequest request = new RefundPostingRequest(orderId, refundId, sellerId, 2_500);
        when(refundReservations.find(refundId)).thenReturn(Optional.of(
                new RefundReservation(
                        refundId, orderId, sellerId, 2_400, "RESERVED", now, now)));

        assertSettlementCode(SettlementException.Code.CONFLICT, () -> service.recordRefund(request));
        verify(journals, never()).insertRefundIfAbsent(any(RefundJournalRecord.class), any(Instant.class));
    }

    @Test
    void postingAnAlreadyReservedRefundDoesNotCancelAPayoutAgain() {
        UUID refundId = UUID.randomUUID();
        RefundPostingRequest request = new RefundPostingRequest(orderId, refundId, sellerId, 2_500);
        when(refundReservations.find(refundId)).thenReturn(Optional.of(
                new RefundReservation(
                        refundId, orderId, sellerId, 2_500, "RESERVED", now, now)));
        when(journals.insertRefundIfAbsent(
                new RefundJournalRecord(orderId, refundId, sellerId, 2_500), now))
                .thenReturn(Optional.of(UUID.randomUUID()));
        when(refundReservations.markPosted(refundId, now)).thenReturn(true);

        service.recordRefund(request);

        verify(payouts, never()).cancelUnsentForRefund(any(UUID.class), any(Instant.class));
    }

    @Test
    void replayingRefundReservationDoesNotCancelPayoutCreatedFromHeldBalance() {
        UUID refundId = UUID.randomUUID();
        RefundPostingRequest request = new RefundPostingRequest(orderId, refundId, sellerId, 2_500);
        when(refundReservations.find(refundId)).thenReturn(Optional.of(
                new RefundReservation(
                        refundId, orderId, sellerId, 2_500, "RESERVED", now, now)));

        service.reserveRefund(request);

        verify(refundReservations, never()).reserve(
                any(UUID.class), any(UUID.class), any(UUID.class), anyLong(), any(Instant.class));
        verify(payouts, never()).cancelUnsentForRefund(any(UUID.class), any(Instant.class));
    }

    @Test
    void replayingDefinitiveRefundReleaseIsIdempotent() {
        UUID refundId = UUID.randomUUID();
        RefundPostingRequest request = new RefundPostingRequest(orderId, refundId, sellerId, 2_500);
        when(refundReservations.find(refundId)).thenReturn(Optional.of(
                new RefundReservation(
                        refundId, orderId, sellerId, 2_500, "RELEASED", now, now)));

        service.releaseRefundReservation(request);

        verify(refundReservations, never()).release(any(UUID.class), any(Instant.class));
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
        assertSettlementCode(SettlementException.Code.INVALID_REQUEST,
                () -> SettlementServiceImpl.validateAndNormalizeCheckoutCapture(
                        new CheckoutSettlementPostingRequest(checkoutId, null, 1,
                                List.of(new SellerOrderAllocationRequest(orderId, sellerId, 1)))));
        assertSettlementCode(SettlementException.Code.INVALID_REQUEST,
                () -> SettlementServiceImpl.validateAndNormalizeCheckoutCapture(
                        new CheckoutSettlementPostingRequest(checkoutId, attemptId, 0,
                                List.of(new SellerOrderAllocationRequest(orderId, sellerId, 1)))));
        assertSettlementCode(SettlementException.Code.INVALID_REQUEST,
                () -> SettlementServiceImpl.validateAndNormalizeCheckoutCapture(
                        new CheckoutSettlementPostingRequest(checkoutId, attemptId, 2,
                                List.of(new SellerOrderAllocationRequest(orderId, sellerId, 1)))));
        assertSettlementCode(SettlementException.Code.INVALID_REQUEST,
                () -> SettlementServiceImpl.validateAndNormalizeCheckoutCapture(
                        new CheckoutSettlementPostingRequest(checkoutId, attemptId, 1,
                                List.of(new SellerOrderAllocationRequest(orderId, sellerId, 0)))));
        assertSettlementCode(SettlementException.Code.INVALID_REQUEST,
                () -> SettlementServiceImpl.validateAndNormalizeCheckoutCapture(
                        new CheckoutSettlementPostingRequest(checkoutId, attemptId, 2, List.of(
                                new SellerOrderAllocationRequest(orderId, sellerId, 1),
                                new SellerOrderAllocationRequest(orderId, UUID.randomUUID(), 1)))));
        assertSettlementCode(SettlementException.Code.INVALID_REQUEST,
                () -> SettlementServiceImpl.validateAndNormalizeCheckoutCapture(
                        new CheckoutSettlementPostingRequest(checkoutId, attemptId, Long.MAX_VALUE, List.of(
                                new SellerOrderAllocationRequest(orderId, sellerId, Long.MAX_VALUE),
                                new SellerOrderAllocationRequest(UUID.randomUUID(), sellerId, 1)))));
    }

    @Test
    void recordsCheckoutEntriesAndAllocationsOnceIncludingAttemptId() {
        UUID checkoutId = UUID.randomUUID();
        UUID attemptId = UUID.randomUUID();
        UUID orderId = UUID.randomUUID();
        UUID sellerId = UUID.randomUUID();
        UUID journalId = UUID.randomUUID();
        Instant recordedAt = Instant.parse("2025-01-02T03:04:05Z");
        SettlementServiceImpl checkoutService = new SettlementServiceImpl(
                journals, payouts, refundReservations, policy, Clock.fixed(recordedAt, ZoneOffset.UTC));
        SellerOrderAllocationRequest allocation = new SellerOrderAllocationRequest(orderId, sellerId, 125);
        CheckoutSettlementRecord facts = new CheckoutSettlementRecord(checkoutId, attemptId, 125,
                List.of(stored(allocation)));
        when(journals.insertCheckoutIfAbsent(facts, recordedAt)).thenReturn(Optional.of(journalId));

        checkoutService.recordCheckoutCapture(new CheckoutSettlementPostingRequest(
                checkoutId, attemptId, 125, List.of(allocation)));

        verify(journals).insertCheckoutIfAbsent(facts, recordedAt);
        // checkout 은 seller 별로 분개한다(요율 0 이면 fee/reserve 0).
        verify(journals).insertEntries(journalId, sellerId, 125, 0, 0, recordedAt);
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
        SettlementServiceImpl checkoutService = new SettlementServiceImpl(
                journals, payouts, refundReservations, policy, Clock.fixed(recordedAt, ZoneOffset.UTC));
        SellerOrderAllocationRequest allocation = new SellerOrderAllocationRequest(orderId, sellerId, 125);
        CheckoutSettlementRecord facts = new CheckoutSettlementRecord(checkoutId, attemptId, 125,
                List.of(stored(allocation)));
        when(journals.insertCheckoutIfAbsent(facts, recordedAt)).thenReturn(Optional.empty());
        when(journals.findCheckoutById(checkoutId)).thenReturn(Optional.of(facts));

        assertDoesNotThrow(() -> checkoutService.recordCheckoutCapture(
                new CheckoutSettlementPostingRequest(checkoutId, attemptId, 125, List.of(allocation))));

        verify(journals).insertCheckoutIfAbsent(facts, recordedAt);
        verify(journals).findCheckoutById(checkoutId);
        verify(journals, never()).insertEntries(any(UUID.class), any(UUID.class), anyLong(),
                anyLong(), anyLong(), any(Instant.class));
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
        SettlementServiceImpl checkoutService = new SettlementServiceImpl(
                journals, payouts, refundReservations, policy, Clock.fixed(recordedAt, ZoneOffset.UTC));
        SellerOrderAllocationRequest originalAllocation = new SellerOrderAllocationRequest(orderId, originalSellerId, 125);
        CheckoutSettlementRecord originalFacts = new CheckoutSettlementRecord(checkoutId, attemptId, 125,
                List.of(stored(originalAllocation)));
        when(journals.insertCheckoutIfAbsent(any(CheckoutSettlementRecord.class), eq(recordedAt)))
                .thenReturn(Optional.empty());
        when(journals.findCheckoutById(checkoutId)).thenReturn(Optional.of(originalFacts));

        assertSettlementCode(SettlementException.Code.CONFLICT, () -> checkoutService.recordCheckoutCapture(
                new CheckoutSettlementPostingRequest(checkoutId, UUID.randomUUID(), 125,
                        List.of(originalAllocation))));
        assertSettlementCode(SettlementException.Code.CONFLICT, () -> checkoutService.recordCheckoutCapture(
                new CheckoutSettlementPostingRequest(checkoutId, attemptId, 125,
                        List.of(new SellerOrderAllocationRequest(orderId, changedSellerId, 125)))));
        assertSettlementCode(SettlementException.Code.CONFLICT, () -> checkoutService.recordCheckoutCapture(
                new CheckoutSettlementPostingRequest(checkoutId, attemptId, 126,
                        List.of(new SellerOrderAllocationRequest(orderId, originalSellerId, 126)))));

        verify(journals, times(3)).insertCheckoutIfAbsent(any(CheckoutSettlementRecord.class), eq(recordedAt));
        verify(journals, times(3)).findCheckoutById(checkoutId);
        verify(journals, never()).insertEntries(any(UUID.class), any(UUID.class), anyLong(),
                anyLong(), anyLong(), any(Instant.class));
        verify(journals, never()).insertCheckoutAllocations(any(UUID.class), anyList());
    }

    private static CheckoutSettlementAllocation stored(SellerOrderAllocationRequest allocation) {
        return new CheckoutSettlementAllocation(allocation.orderId(), allocation.sellerId(), allocation.amountWon());
    }

    @Test
    void sellerStatementRejectsInvalidRangeAndPageLimits() {
        SettlementServiceImpl statementService = new SettlementServiceImpl(
                journals, payouts, refundReservations, policy, Clock.fixed(now, ZoneOffset.UTC));
        UUID sellerId = UUID.randomUUID();
        Instant from = Instant.parse("2025-01-01T00:00:00Z");
        Instant to = Instant.parse("2025-02-01T00:00:00Z");
        assertInvalidStatement(statementService, new SellerSettlementStatementRequest(sellerId, to, from, 10, null));
        assertInvalidStatement(statementService, new SellerSettlementStatementRequest(sellerId, from, to, 0, null));
        assertInvalidStatement(statementService, new SellerSettlementStatementRequest(sellerId, from, to, 101, null));
    }

    @Test
    void sellerStatementRejectsMalformedAndWrongScopeCursors() {
        SettlementServiceImpl statementService = new SettlementServiceImpl(
                journals, payouts, refundReservations, policy, Clock.fixed(now, ZoneOffset.UTC));
        UUID sellerId = UUID.randomUUID();
        Instant from = Instant.parse("2025-01-01T00:00:00Z");
        Instant to = Instant.parse("2025-02-01T00:00:00Z");
        assertInvalidStatement(statementService, new SellerSettlementStatementRequest(sellerId, from, to, 10, "!"));
        assertInvalidStatement(statementService, new SellerSettlementStatementRequest(sellerId, from, to, 10,
                cursor("v2|" + sellerId + "|" + from + "|" + to + "|" + from + "|" + UUID.randomUUID())));
        assertInvalidStatement(statementService, new SellerSettlementStatementRequest(sellerId, from, to, 10,
                cursor("v1|" + sellerId + "|" + from + "|" + to + "|not-an-instant|" + UUID.randomUUID())));
        assertInvalidStatement(statementService, new SellerSettlementStatementRequest(UUID.randomUUID(), from, to, 10,
                cursor("v1|" + sellerId + "|" + from + "|" + to + "|" + from + "|" + UUID.randomUUID())));
    }

    @Test
    void sellerStatementSurfacesPayoutRowsWithSignedAmountWithoutDoubleCountingGross() {
        SettlementServiceImpl statementService = new SettlementServiceImpl(journals, payouts,
                refundReservations, policy, Clock.fixed(now, ZoneOffset.UTC));
        UUID statementSellerId = UUID.randomUUID();
        Instant from = Instant.parse("2025-01-01T00:00:00Z");
        Instant to = Instant.parse("2025-02-01T00:00:00Z");
        UUID payoutJournalId = UUID.randomUUID();
        UUID payoutId = UUID.randomUUID();
        Instant payoutRecordedAt = from.plusSeconds(60);
        UUID captureJournalId = UUID.randomUUID();
        UUID captureOrderId = UUID.randomUUID();
        when(journals.sellerStatementTotals(statementSellerId, from, to))
                .thenReturn(new SellerStatementTotals(BigInteger.valueOf(10_000), BigInteger.ZERO,
                        BigInteger.valueOf(10_000), BigInteger.valueOf(1_000), BigInteger.valueOf(1_000),
                        BigInteger.valueOf(8_000)));
        when(journals.sellerBalance(statementSellerId))
                .thenReturn(new SellerBalance(BigInteger.ZERO, BigInteger.valueOf(1_000),
                        BigInteger.valueOf(1_000)));
        when(payouts.pendingAmount(statementSellerId)).thenReturn(0L);
        when(payouts.confirmedAmount(statementSellerId)).thenReturn(8_000L);
        when(journals.sellerStatementRows(statementSellerId, from, to, null, null, 11))
                .thenReturn(List.of(
                        new SellerStatementRow(captureJournalId, captureOrderId, UUID.randomUUID(),
                                "ORDER_CAPTURE", from, 10_000),
                        new SellerStatementRow(payoutJournalId, null, payoutId, "PAYOUT",
                                payoutRecordedAt, -8_000)));

        SellerSettlementStatementResponse statement = statementService.getSellerStatement(
                new SellerSettlementStatementRequest(statementSellerId, from, to, 10, null));

        assertThat(statement.items()).hasSize(2);
        assertThat(statement.items().get(0).type())
                .isEqualTo(SellerSettlementStatementResponse.EntryType.ORDER_CAPTURE);
        // 지급은 gross 를 늘리지 않고 지급 가능 잔액만 줄인다. source 는 payout 이다.
        assertThat(statement.items().get(1).type()).isEqualTo(SellerSettlementStatementResponse.EntryType.PAYOUT);
        assertThat(statement.items().get(1).sourceId()).isEqualTo(payoutId);
        assertThat(statement.items().get(1).orderId()).isNull();
        assertThat(statement.items().get(1).signedAmountWon()).isEqualTo(-8_000L);
        // page 가 limit(10)을 넘지 않으므로 다음 cursor 는 없다.
        assertThat(statement.nextCursor()).isNull();
        // 지급액은 기간 흐름이 아니라 payout 상태 기계 합계로만 드러난다(captured 중복 없음).
        assertThat(statement.totals().capturedAmountWon()).isEqualTo(BigInteger.valueOf(10_000));
        assertThat(statement.totals().netLedgerChangeWon()).isEqualTo(BigInteger.valueOf(10_000));
        // 수수료·보류·지급은 gross 와 별도 필드다. captured 에 더하지 않으므로 이중 계산이 없다.
        assertThat(statement.totals().periodFeeAmountWon()).isEqualTo(BigInteger.valueOf(1_000));
        assertThat(statement.totals().periodReserveAmountWon()).isEqualTo(BigInteger.valueOf(1_000));
        assertThat(statement.totals().paidOutAmountWon()).isEqualTo(BigInteger.valueOf(8_000));
        assertThat(statement.totals().feeAmountWon()).isEqualTo(BigInteger.valueOf(1_000));
        assertThat(statement.totals().reserveAmountWon()).isEqualTo(BigInteger.valueOf(1_000));
        // 이 page 가 기간 전체를 담으므로 page row 합 = captured + refundReversal − paidOut 이다.
        // (여러 page 로 나뉘면 이 항등식은 전체 page 를 합쳤을 때 성립한다.)
        BigInteger rowSum = statement.items().stream()
                .map(entry -> BigInteger.valueOf(entry.signedAmountWon()))
                .reduce(BigInteger.ZERO, BigInteger::add);
        assertThat(rowSum).isEqualTo(statement.totals().capturedAmountWon()
                .add(statement.totals().refundReversalAmountWon())
                .subtract(statement.totals().paidOutAmountWon()));
        assertThat(statement.totals().confirmedPayoutAmountWon()).isEqualTo(BigInteger.valueOf(8_000));
        assertThat(statement.totals().pendingPayoutAmountWon()).isZero();
    }

    @Test
    void payoutStatementPageKeepsTheV1CursorContract() {
        SettlementServiceImpl statementService = new SettlementServiceImpl(journals, payouts,
                refundReservations, policy, Clock.fixed(now, ZoneOffset.UTC));
        UUID statementSellerId = UUID.randomUUID();
        Instant from = Instant.parse("2025-01-01T00:00:00Z");
        Instant to = Instant.parse("2025-02-01T00:00:00Z");
        UUID payoutJournalId = UUID.randomUUID();
        Instant payoutRecordedAt = from.plusSeconds(60);
        when(journals.sellerStatementTotals(statementSellerId, from, to))
                .thenReturn(new SellerStatementTotals(BigInteger.ZERO, BigInteger.ZERO, BigInteger.ZERO,
                        BigInteger.ZERO, BigInteger.ZERO, BigInteger.ZERO));
        when(journals.sellerBalance(statementSellerId))
                .thenReturn(new SellerBalance(BigInteger.ZERO, BigInteger.ZERO, BigInteger.ZERO));
        when(journals.sellerStatementRows(statementSellerId, from, to, null, null, 2))
                .thenReturn(List.of(
                        new SellerStatementRow(payoutJournalId, null, UUID.randomUUID(), "PAYOUT",
                                payoutRecordedAt, -8_000),
                        // limit+1 번째 row 는 다음 page 존재만 알려주고 응답에는 남지 않는다.
                        new SellerStatementRow(UUID.randomUUID(), null, UUID.randomUUID(), "PAYOUT",
                                payoutRecordedAt.plusSeconds(1), -1_000)));

        SellerSettlementStatementResponse firstPage = statementService.getSellerStatement(
                new SellerSettlementStatementRequest(statementSellerId, from, to, 1, null));

        assertThat(firstPage.items()).singleElement().satisfies(entry -> {
            assertThat(entry.type()).isEqualTo(SellerSettlementStatementResponse.EntryType.PAYOUT);
            assertThat(entry.journalId()).isEqualTo(payoutJournalId);
        });
        assertThat(firstPage.nextCursor()).isNotBlank();
        // cursor 는 v1|seller|from|to|recordedAt|journalId 형식 그대로다.
        assertThat(new String(Base64.getUrlDecoder().decode(firstPage.nextCursor()), StandardCharsets.UTF_8))
                .isEqualTo("v1|" + statementSellerId + "|" + from + "|" + to + "|"
                        + payoutRecordedAt + "|" + payoutJournalId);
    }

    private static void assertSettlementCode(SettlementException.Code code, Runnable action) {
        SettlementException exception = assertThrows(SettlementException.class, action::run);
        assertEquals(code, exception.code());
    }

    private static void assertInvalidStatement(SettlementServiceImpl statementService,
                                               SellerSettlementStatementRequest request) {
        SettlementException exception = assertThrows(SettlementException.class,
                () -> statementService.getSellerStatement(request));
        assertEquals(SettlementException.Code.INVALID_REQUEST, exception.code());
    }

    private static String cursor(String value) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

}
