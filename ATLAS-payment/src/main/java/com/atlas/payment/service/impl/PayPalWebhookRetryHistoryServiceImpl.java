package com.atlas.payment.service.impl;

import com.atlas.payment.dto.response.PayPalWebhookPendingRetryPage;
import com.atlas.payment.dto.response.PayPalWebhookRetryAttemptHistoryResponse;
import com.atlas.payment.dto.response.PayPalWebhookRetrySummary;
import com.atlas.payment.exception.PaymentException.Code;
import com.atlas.payment.exception.PaymentException;
import com.atlas.payment.persistence.projection.RetrySummary;
import com.atlas.payment.repository.PayPalWebhookRetryHistoryRepository;
import com.atlas.payment.service.PayPalWebhookRetryHistoryService;
import java.util.List;
import java.util.Objects;
import org.springframework.stereotype.Service;

@Service
public class PayPalWebhookRetryHistoryServiceImpl implements PayPalWebhookRetryHistoryService {
    private final PayPalWebhookRetryHistoryRepository retries;

    public PayPalWebhookRetryHistoryServiceImpl(PayPalWebhookRetryHistoryRepository retries) {
        this.retries = Objects.requireNonNull(retries);
    }

    /** 최근 24시간 재시도 outcome 요약. */
    @Override
    public PayPalWebhookRetrySummary summary() {
        RetrySummary summary = retries.summary();
        return new PayPalWebhookRetrySummary(summary.pending(), summary.exhausted(), summary.oldestPendingSince(),
                summary.oldestExhaustedSince(), summary.expiredLeaseCount(), summary.last24HoursOutcomeCounts());
    }

    @Override
    public PayPalWebhookPendingRetryPage pending(int page, int size) {
        long offset = offset(page, size);
        long total = retries.countPending();
        List<PayPalWebhookPendingRetryPage.Item> items = retries.listPending(size, offset).stream()
                .map(item -> new PayPalWebhookPendingRetryPage.Item(item.eventId(), item.eventType(),
                        item.attemptCount(), item.nextAttemptAt(), item.leaseState()))
                .toList();
        return new PayPalWebhookPendingRetryPage(total, page, size, items);
    }

    @Override
    public PayPalWebhookRetryAttemptHistoryResponse history(String eventId, int page, int size) {
        long offset = offset(page, size);
        if (!retries.eventKnown(eventId)) {
            throw new PaymentException(Code.NOT_FOUND, "Retry event not found");
        }
        long total = retries.countTimeline(eventId);
        List<PayPalWebhookRetryAttemptHistoryResponse.Entry> entries = retries.timeline(eventId, size, offset).stream()
                .map(entry -> new PayPalWebhookRetryAttemptHistoryResponse.Entry(entry.category(), entry.recordedAt(),
                        entry.attemptNumber()))
                .toList();
        return new PayPalWebhookRetryAttemptHistoryResponse(eventId, total, page, size, entries);
    }

    private static long offset(int page, int size) {
        if (page < 0 || size < 1 || size > MAX_PAGE_SIZE) {
            throw new PaymentException(Code.INVALID_REQUEST,
                    "page must be non-negative and size must be between 1 and " + MAX_PAGE_SIZE);
        }
        long offset = (long) page * size;
        if (offset > MAX_PAGE_OFFSET) {
            throw new PaymentException(Code.INVALID_REQUEST, "page offset is too large");
        }
        return offset;
    }
}
