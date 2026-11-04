package com.atlas.settlement.service.impl;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.atlas.settlement.dto.request.CapturePostingRequest;
import com.atlas.settlement.repository.SettlementJournalRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
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

    @Test
    void postsTwoEntriesOnceForNewCapture() {
        UUID journalId = UUID.randomUUID();
        when(journals.insertIfAbsent(request, now)).thenReturn(Optional.of(journalId));

        service.recordCapture(request);

        verify(journals).insertEntries(journalId, 2_500, now);
        verify(journals, never()).findByAttemptId(attemptId);
    }

    @Test
    void treatsIdenticalAttemptReplayAsIdempotent() {
        when(journals.insertIfAbsent(request, now)).thenReturn(Optional.empty());
        when(journals.findByAttemptId(attemptId)).thenReturn(Optional.of(request));

        service.recordCapture(request);

        verify(journals, never()).insertEntries(any(UUID.class), anyLong(), any(Instant.class));
    }

    @Test
    void rejectsReplayWithDifferentCaptureFacts() {
        when(journals.insertIfAbsent(request, now)).thenReturn(Optional.empty());
        when(journals.findByAttemptId(attemptId)).thenReturn(Optional.of(
                new CapturePostingRequest(orderId, attemptId, sellerId, 2_400)));

        assertThrows(IllegalStateException.class, () -> service.recordCapture(request));
    }

    @Test
    void rejectsInvalidCaptureFactsBeforePersistence() {
        assertThrows(IllegalArgumentException.class,
                () -> service.recordCapture(new CapturePostingRequest(orderId, null, sellerId, 2_500)));

        verify(journals, never()).insertIfAbsent(any(CapturePostingRequest.class), any(Instant.class));
    }
}
