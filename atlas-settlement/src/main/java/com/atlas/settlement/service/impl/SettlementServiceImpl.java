package com.atlas.settlement.service.impl;

import com.atlas.settlement.dto.request.CapturePostingRequest;
import com.atlas.settlement.dto.request.CheckoutSettlementPostingRequest;
import com.atlas.settlement.dto.request.RefundPostingRequest;
import com.atlas.settlement.dto.request.SellerSettlementStatementRequest;
import com.atlas.settlement.dto.response.SellerSettlementStatementResponse;
import com.atlas.settlement.exception.SettlementException;
import com.atlas.settlement.dto.request.SellerOrderAllocationRequest;
import com.atlas.settlement.persistence.projection.CaptureJournalRecord;
import com.atlas.settlement.persistence.projection.CheckoutSettlementAllocation;
import com.atlas.settlement.persistence.projection.CheckoutSettlementRecord;
import com.atlas.settlement.persistence.projection.RefundJournalRecord;
import com.atlas.settlement.persistence.projection.SellerStatementRow;
import org.springframework.transaction.annotation.Isolation;
import com.atlas.settlement.repository.SettlementJournalRepository;
import com.atlas.settlement.repository.SettlementPayoutRepository;
import com.atlas.settlement.repository.SettlementRefundReservationRepository;
import com.atlas.settlement.persistence.projection.RefundReservation;
import com.atlas.settlement.service.SettlementService;
import java.time.Clock;
import java.time.Instant;
import java.time.DateTimeException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class SettlementServiceImpl implements SettlementService {
    private final SettlementJournalRepository journals;
    private final SettlementPayoutRepository payoutTotals;
    private final SettlementRefundReservationRepository refundReservations;
    private final SettlementPolicy policy;
    private final Clock clock;

    public SettlementServiceImpl(SettlementJournalRepository journals,
            SettlementPayoutRepository payoutTotals,
            SettlementRefundReservationRepository refundReservations,
            SettlementPolicy policy, Clock clock) {
        this.journals = Objects.requireNonNull(journals);
        this.payoutTotals = Objects.requireNonNull(payoutTotals);
        this.refundReservations = Objects.requireNonNull(refundReservations);
        this.policy = Objects.requireNonNull(policy);
        this.clock = Objects.requireNonNull(clock);
    }

    @Override
    @Transactional
    public void recordCapture(CapturePostingRequest request) {
        validate(request);
        CaptureJournalRecord facts = new CaptureJournalRecord(
                request.orderId(), request.attemptId(), request.sellerId(), request.grossAmountWon());
        Instant recordedAt = clock.instant();
        // 수수료·보류를 capture 시점 정책으로 분해하고, 적용 요율을 journal 에 스냅샷한다.
        SettlementPolicy.Split split = policy.split(request.grossAmountWon());
        Optional<UUID> journalId = journals.insertIfAbsent(facts, split.feeAmountWon(),
                split.reserveAmountWon(), split.feeRateBps(), split.reserveRateBps(), recordedAt);
        if (journalId.isPresent()) {
            journals.insertEntries(journalId.get(), request.sellerId(), request.grossAmountWon(),
                    split.feeAmountWon(), split.reserveAmountWon(), recordedAt);
            return;
        }
        CaptureJournalRecord existing = journals.findByAttemptId(request.attemptId())
                .orElseThrow(() -> new IllegalStateException("Capture journal conflict disappeared"));
        if (!existing.equals(facts)) {
            throw conflict("Capture attempt was posted with different facts");
        }
    }

    @Override
    @Transactional
    public void recordCheckoutCapture(CheckoutSettlementPostingRequest request) {
        CheckoutSettlementRecord facts = validateAndNormalizeCheckoutCapture(request);
        Instant recordedAt = clock.instant();
        Optional<UUID> journalId = journals.insertCheckoutIfAbsent(facts, recordedAt);
        if (journalId.isPresent()) {
            UUID id = journalId.get();
            // checkout 은 seller 별로 수수료·보류를 계산한다. seller 별 entry 를 기록해,
            // seller statement 가 정확히 자기 몫을 본다.
            for (CheckoutSettlementAllocation allocation : facts.allocations()) {
                SettlementPolicy.Split split = policy.split(allocation.amountWon());
                journals.insertEntries(id, allocation.sellerId(), allocation.amountWon(),
                        split.feeAmountWon(), split.reserveAmountWon(), recordedAt);
            }
            journals.insertCheckoutAllocations(id, facts.allocations());
            return;
        }
        CheckoutSettlementRecord existing = journals.findCheckoutById(facts.checkoutId())
                .orElseThrow(() -> new IllegalStateException("Checkout settlement was not visible after conflict"));
        if (!existing.equals(facts)) {
            throw conflict("Checkout settlement replay does not match original facts");
        }
    }

    static CheckoutSettlementRecord validateAndNormalizeCheckoutCapture(CheckoutSettlementPostingRequest request) {
        if (request == null || request.checkoutId() == null || request.attemptId() == null
                || request.totalAmountWon() <= 0 || request.allocations() == null || request.allocations().isEmpty()) {
            throw invalid("Checkout, attempt, and positive settlement allocations are required");
        }
        List<CheckoutSettlementAllocation> allocations = new ArrayList<>();
        Set<UUID> orderIds = new HashSet<>();
        long total = 0;
        for (SellerOrderAllocationRequest allocation : request.allocations()) {
            if (allocation == null || allocation.orderId() == null || allocation.sellerId() == null
                    || allocation.amountWon() <= 0 || !orderIds.add(allocation.orderId())) {
                throw invalid("Checkout allocations must have unique orders and positive amounts");
            }
            try {
                total = Math.addExact(total, allocation.amountWon());
            } catch (ArithmeticException exception) {
                throw invalid("Checkout allocation total exceeds supported amount");
            }
            allocations.add(new CheckoutSettlementAllocation(
                    allocation.orderId(), allocation.sellerId(), allocation.amountWon()));
        }
        if (total != request.totalAmountWon()) {
            throw invalid("Checkout total must equal the sum of seller allocations");
        }
        allocations.sort(Comparator.comparing(CheckoutSettlementAllocation::orderId));
        return new CheckoutSettlementRecord(request.checkoutId(), request.attemptId(), request.totalAmountWon(),
                List.copyOf(allocations));
    }

    @Override
    @Transactional
    public void recordRefund(RefundPostingRequest request) {
        validate(request);
        journals.lockSellerPayouts(request.sellerId());
        Instant recordedAt = clock.instant();
        var reservation = refundReservations.find(request.refundId())
                .orElseThrow(() -> conflict("Refund reservation must exist before posting"));
        validateRefundReservation(reservation, request);
        if (!"RESERVED".equals(reservation.status()) && !"POSTED".equals(reservation.status())) {
            throw conflict("Refund reservation is not active");
        }
        RefundJournalRecord facts = new RefundJournalRecord(
                request.orderId(), request.refundId(), request.sellerId(), request.amountWon());
        Optional<UUID> journalId = journals.insertRefundIfAbsent(facts, recordedAt);
        if (journalId.isPresent()) {
            journals.insertRefundEntries(journalId.get(), request.sellerId(), request.amountWon(), recordedAt);
            requireRefundReservationPosted(request.refundId(), recordedAt);
            return;
        }
        RefundJournalRecord existing = journals.findByRefundId(request.refundId())
                .orElseThrow(() -> new IllegalStateException("Refund journal conflict disappeared"));
        if (!existing.equals(facts)) {
            throw conflict("Refund was posted with different facts");
        }
        requireRefundReservationPosted(request.refundId(), recordedAt);
    }

    @Override
    @Transactional
    public void reserveRefund(RefundPostingRequest request) {
        validate(request);
        journals.lockSellerPayouts(request.sellerId());
        Instant now = clock.instant();
        var existing = refundReservations.find(request.refundId());
        if (existing.isPresent()) {
            validateRefundReservation(existing.get(), request);
            if ("RESERVED".equals(existing.get().status()) || "POSTED".equals(existing.get().status())) return;
            throw conflict("Refund reservation is not active");
        }
        var reservation = refundReservations.reserve(
                request.refundId(), request.orderId(), request.sellerId(), request.amountWon(), now);
        validateRefundReservation(reservation, request);
        if (!"RESERVED".equals(reservation.status())) throw conflict("Refund reservation is not active");
        payoutTotals.cancelUnsentForRefund(request.sellerId(), now);
    }

    @Override
    @Transactional
    public void releaseRefundReservation(RefundPostingRequest request) {
        validate(request);
        journals.lockSellerPayouts(request.sellerId());
        var reservation = refundReservations.find(request.refundId())
                .orElseThrow(() -> conflict("Refund reservation was not found"));
        validateRefundReservation(reservation, request);
        if ("POSTED".equals(reservation.status())) throw conflict("Posted refund reservation cannot be released");
        if ("RELEASED".equals(reservation.status())) return;
        if (!refundReservations.release(request.refundId(), clock.instant())) {
            throw conflict("Refund reservation changed before release");
        }
    }

    private static void validateRefundReservation(
            RefundReservation reservation, RefundPostingRequest request) {
        if (!reservation.refundId().equals(request.refundId()) || !reservation.orderId().equals(request.orderId())
                || !reservation.sellerId().equals(request.sellerId())
                || reservation.amountWon() != request.amountWon()) {
            throw conflict("Refund reservation facts do not match");
        }
    }

    private void requireRefundReservationPosted(UUID refundId, Instant now) {
        if (!refundReservations.markPosted(refundId, now)) {
            throw conflict("Refund reservation could not be consumed");
        }
    }

    private static void validate(CapturePostingRequest request) {
        if (request == null || request.orderId() == null || request.attemptId() == null || request.sellerId() == null
                || request.grossAmountWon() <= 0) {
            throw invalid("Capture posting facts are invalid");
        }
    }

    private static void validate(RefundPostingRequest request) {
        if (request == null || request.orderId() == null || request.refundId() == null || request.sellerId() == null
                || request.amountWon() <= 0) {
            throw invalid("Refund posting facts are invalid");
        }
    }
    @Override
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public SellerSettlementStatementResponse getSellerStatement(SellerSettlementStatementRequest request) {
        if (request == null || request.sellerId() == null || request.from() == null || request.to() == null
                || !request.from().isBefore(request.to()) || request.limit() < 1 || request.limit() > 100) {
            throw invalidStatementRequest();
        }
        int pageSize = request.limit();
        Instant afterAt = null;
        UUID afterId = null;
        if (request.cursor() != null) {
            try {
                String decoded = new String(Base64.getUrlDecoder().decode(request.cursor()), StandardCharsets.UTF_8);
                String[] parts = decoded.split("\\|", -1);
                if (parts.length != 6 || !parts[0].equals("v1")
                        || !parts[1].equals(request.sellerId().toString())
                        || !parts[2].equals(request.from().toString()) || !parts[3].equals(request.to().toString())) {
                    throw invalidStatementRequest();
                }
                afterAt = Instant.parse(parts[4]);
                afterId = UUID.fromString(parts[5]);
            } catch (IllegalArgumentException | DateTimeException exception) {
                throw invalidStatementRequest();
            }
        }
        var totals = journals.sellerStatementTotals(request.sellerId(), request.from(), request.to());
        var balance = journals.sellerBalance(request.sellerId());
        List<SellerStatementRow> rows = journals.sellerStatementRows(request.sellerId(),
                request.from(), request.to(), afterAt, afterId, pageSize + 1);
        boolean hasMore = rows.size() > pageSize;
        if (hasMore) rows = rows.subList(0, pageSize);
        List<SellerSettlementStatementResponse.Entry> items = rows.stream().map(row ->
                new SellerSettlementStatementResponse.Entry(row.journalId(), row.orderId(), row.sourceId(),
                        SellerSettlementStatementResponse.EntryType.valueOf(row.type()), row.recordedAt(),
                        row.signedAmountWon())).toList();
        String next = null;
        if (hasMore) {
            var last = rows.get(rows.size() - 1);
            String token = "v1|" + request.sellerId() + "|" + request.from() + "|" + request.to() + "|"
                    + last.recordedAt() + "|" + last.journalId();
            next = Base64.getUrlEncoder().withoutPadding().encodeToString(token.getBytes(StandardCharsets.UTF_8));
        }
        return new SellerSettlementStatementResponse(request.sellerId(), request.from(), request.to(),
                new SellerSettlementStatementResponse.Summary(totals.capturedAmountWon(),
                        totals.refundReversalAmountWon(), totals.netLedgerChangeWon(),
                        // 기간 흐름(fee/reserve/paidOut)과 현재 잔액을 모두 노출한다.
                        // 어느 쪽도 captured/net 에 더하지 않는다(이중 계산 방지).
                        totals.feeAmountWon(), totals.reserveAmountWon(), totals.paidOutAmountWon(),
                        balance.feeAmountWon(), balance.reserveAmountWon(),
                        balance.payableAmountWon(),
                        BigInteger.valueOf(payoutTotals.pendingAmount(request.sellerId())),
                        BigInteger.valueOf(payoutTotals.confirmedAmount(request.sellerId()))),
                items, next);
    }

    private static SettlementException invalidStatementRequest() {
        return invalid("Seller statement request is invalid");
    }

    private static SettlementException invalid(String message) {
        return new SettlementException(SettlementException.Code.INVALID_REQUEST, message);
    }

    private static SettlementException conflict(String message) {
        return new SettlementException(SettlementException.Code.CONFLICT, message);
    }

}