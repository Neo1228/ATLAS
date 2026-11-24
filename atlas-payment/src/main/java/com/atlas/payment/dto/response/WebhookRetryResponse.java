package com.atlas.payment.dto.response;

import java.time.Instant;
import java.util.List;
import java.util.Map;

public final class WebhookRetryResponse {
    private WebhookRetryResponse() {
    }

    /** 대기열에서 lease를 얻은 웹훅 재시도 한 건. */
    public record ClaimedWebhookRetry(PaymentWebhookEventResponse event, int attempt, String leaseOwner) {
    }

    public record ExhaustedRetryPage(
            List<Item> content,
            int page,
            int size,
            long totalElements,
            int totalPages) {

        public ExhaustedRetryPage {
            content = List.copyOf(content);
        }

        public record Item(String eventId, String eventType, int attemptCount, Instant createdAt, Instant finishedAt) { }
    }

    public record PendingRetryPage(long total, int page, int size, List<Item> items) {
        public PendingRetryPage {
            items = List.copyOf(items);
        }

        public record Item(String eventId, String eventType, int attemptCount,
                Instant nextAttemptAt, String leaseState) { }
    }

    public record Requeue(String eventId, String outcome) { }

    public record RetryAttemptHistory(String eventId, long total, int page, int size,
            List<Entry> entries) {
        public RetryAttemptHistory {
            entries = List.copyOf(entries);
        }

        // 수동 감사 항목은 분류와 시각만 포함하며 attemptNumber는 null입니다.
        public record Entry(String category, Instant recordedAt, Integer attemptNumber) { }
    }

    public record RetrySummary(long pending, long exhausted,
            Instant oldestPendingSince, Instant oldestExhaustedSince, long expiredLeaseCount,
            Map<String, Long> last24HoursOutcomeCounts) {
        public RetrySummary {
            last24HoursOutcomeCounts = Map.copyOf(last24HoursOutcomeCounts);
        }
    }

    /** 한 번의 poll 결과. 처리할 재시도와 이번에 소진 처리한 건수. */
    public record RetryPoll(List<ClaimedWebhookRetry> claimed, int reapedCount) {
        public RetryPoll {
            claimed = List.copyOf(claimed);
        }
    }
}
