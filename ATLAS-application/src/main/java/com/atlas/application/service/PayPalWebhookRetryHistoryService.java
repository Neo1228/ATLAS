package com.atlas.application.service;

import com.atlas.application.dto.response.PayPalWebhookPendingRetryPage;
import com.atlas.application.dto.response.PayPalWebhookRetryAttemptHistory;
import com.atlas.application.dto.response.PayPalWebhookRetrySummary;
import java.util.List;
import java.util.Objects;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class PayPalWebhookRetryHistoryService {
    public static final int DEFAULT_PAGE_SIZE = 20;
    public static final int MAX_PAGE_SIZE = 100;
    public static final int MAX_PAGE_OFFSET = 10_000;
    private final PayPalWebhookRetryHistoryRepository retries;

    public PayPalWebhookRetryHistoryService(PayPalWebhookRetryHistoryRepository retries) {
        this.retries = Objects.requireNonNull(retries);
    }

    /** 최근 24시간 재시도 outcome 요약. */
    public PayPalWebhookRetrySummary summary() {
        return retries.summary();
    }

    public PayPalWebhookPendingRetryPage pending(int page, int size) {
        long offset = offset(page, size);
        long total = retries.countPending();
        List<PayPalWebhookPendingRetryPage.Item> items = retries.listPending(size, offset);
        return new PayPalWebhookPendingRetryPage(total, page, size, items);
    }

    public PayPalWebhookRetryAttemptHistory history(String eventId, int page, int size) {
        long offset = offset(page, size);
        if (!retries.eventKnown(eventId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Retry event not found");
        }
        long total = retries.countTimeline(eventId);
        List<PayPalWebhookRetryAttemptHistory.Entry> entries = retries.timeline(eventId, size, offset);
        return new PayPalWebhookRetryAttemptHistory(eventId, total, page, size, entries);
    }

    private static long offset(int page, int size) {
        if (page < 0 || size < 1 || size > MAX_PAGE_SIZE) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "page must be non-negative and size must be between 1 and " + MAX_PAGE_SIZE);
        }
        long offset = (long) page * size;
        if (offset > MAX_PAGE_OFFSET) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "page offset is too large");
        }
        return offset;
    }
}
