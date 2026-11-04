package com.atlas.settlement.service.impl;

import com.atlas.settlement.dto.request.CapturePostingRequest;
import com.atlas.settlement.repository.SettlementJournalRepository;
import com.atlas.settlement.service.SettlementService;
import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
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

    private static void validate(CapturePostingRequest request) {
        if (request == null || request.orderId() == null || request.attemptId() == null || request.sellerId() == null
                || request.grossAmountWon() <= 0) {
            throw new IllegalArgumentException("Capture posting facts are invalid");
        }
    }
}
