package com.atlas.settlement.service.impl;

import com.atlas.settlement.dto.request.CapturePostingRequest;
import com.atlas.settlement.dto.request.RefundPostingRequest;
import com.atlas.settlement.repository.SettlementJournalRepository;
import com.atlas.settlement.service.SettlementService;
import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
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
        Instant recordedAt = clock.instant();
        var journalId = journals.insertIfAbsent(request, recordedAt);
        if (journalId.isPresent()) {
            journals.insertEntries(journalId.get(), request.grossAmountWon(), recordedAt);
            return;
        }
        CapturePostingRequest existing = journals.findByAttemptId(request.attemptId())
                .orElseThrow(() -> new IllegalStateException("Capture journal conflict disappeared"));
        if (!existing.equals(request)) {
            throw new IllegalStateException("Capture attempt was posted with different facts");
        }
    }

    @Override
    @Transactional
    public void recordRefund(RefundPostingRequest request) {
        validate(request);
        Instant recordedAt = clock.instant();
        var journalId = journals.insertRefundIfAbsent(request, recordedAt);
        if (journalId.isPresent()) {
            journals.insertRefundEntries(journalId.get(), request.amountWon(), recordedAt);
            return;
        }
        RefundPostingRequest existing = journals.findByRefundId(request.refundId())
                .orElseThrow(() -> new IllegalStateException("Refund journal conflict disappeared"));
        if (!existing.equals(request)) {
            throw new IllegalStateException("Refund was posted with different facts");
        }
    }

    private static void validate(CapturePostingRequest request) {
        if (request == null || request.orderId() == null || request.attemptId() == null || request.sellerId() == null
                || request.grossAmountWon() <= 0) {
            throw new IllegalArgumentException("Capture posting facts are invalid");
        }
    }

    private static void validate(RefundPostingRequest request) {
        if (request == null || request.orderId() == null || request.refundId() == null
                || request.sellerId() == null || request.amountWon() <= 0) {
            throw new IllegalArgumentException("Refund posting facts are invalid");
        }
    }
}
