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
import com.atlas.settlement.service.SettlementService;
import java.time.Clock;
import java.time.Instant;
import java.time.DateTimeException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
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
    private final Clock clock;

    public SettlementServiceImpl(SettlementJournalRepository journals, Clock clock) {
        this.journals = Objects.requireNonNull(journals);
        this.clock = Objects.requireNonNull(clock);
    }

    @Override
    @Transactional
    public void recordCapture(CapturePostingRequest request) {
        validate(request);
        CaptureJournalRecord facts = new CaptureJournalRecord(
                request.orderId(), request.attemptId(), request.sellerId(), request.grossAmountWon());
        Instant recordedAt = clock.instant();
        Optional<UUID> journalId = journals.insertIfAbsent(facts, recordedAt);
        if (journalId.isPresent()) {
            journals.insertEntries(journalId.get(), request.grossAmountWon(), recordedAt);
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
            journals.insertEntries(id, facts.totalAmountWon(), recordedAt);
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
        RefundJournalRecord facts = new RefundJournalRecord(
                request.orderId(), request.refundId(), request.sellerId(), request.amountWon());
        Instant recordedAt = clock.instant();
        Optional<UUID> journalId = journals.insertRefundIfAbsent(facts, recordedAt);
        if (journalId.isPresent()) {
            journals.insertRefundEntries(journalId.get(), request.amountWon(), recordedAt);
            return;
        }
        RefundJournalRecord existing = journals.findByRefundId(request.refundId())
                .orElseThrow(() -> new IllegalStateException("Refund journal conflict disappeared"));
        if (!existing.equals(facts)) {
            throw conflict("Refund was posted with different facts");
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
                        totals.refundReversalAmountWon(), totals.netLedgerChangeWon()), items, next);
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
