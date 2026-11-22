package com.atlas.application.service;

import com.atlas.application.dto.response.PayPalWebhookExhaustedRetryPage;
import com.atlas.application.dto.response.PayPalWebhookExhaustedRetryPage.Item;
import com.atlas.application.dto.response.PayPalWebhookRequeueResponse;
import com.atlas.application.service.PayPalWebhookOperatorRepository.RequeueRecord;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

@Service
public class PayPalWebhookOperatorService {
    public static final int DEFAULT_PAGE_SIZE = 20;
    public static final int MAX_PAGE_SIZE = 100;
    public static final int MAX_PAGE_OFFSET = 10_000;
    public static final int MAX_MANUAL_REQUEUES = 3;
    private static final int MAX_REASON_LENGTH = 500;
    private static final int MAX_IDEMPOTENCY_KEY_LENGTH = 128;
    private static final int MAX_ACTOR_LENGTH = 128;

    private final PayPalWebhookOperatorRepository events;
    private final TransactionTemplate transactions;

    public PayPalWebhookOperatorService(
            PayPalWebhookOperatorRepository events, PlatformTransactionManager transactionManager) {
        this.events = Objects.requireNonNull(events);
        this.transactions = new TransactionTemplate(Objects.requireNonNull(transactionManager));
    }

    public PayPalWebhookExhaustedRetryPage listExhausted(int page, int requestedSize) {
        if (page < 0) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "page must be non-negative");
        int size = requestedSize <= 0 ? DEFAULT_PAGE_SIZE : Math.min(requestedSize, MAX_PAGE_SIZE);
        long offset = (long) page * size;
        if (offset > MAX_PAGE_OFFSET) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "page offset is too large");
        }
        long count = events.countExhausted();
        List<Item> items = events.listExhausted(size, offset).stream()
                .map(row -> new Item(row.eventId(), row.eventType(), row.attemptCount(),
                        row.createdAt(), row.finishedAt()))
                .toList();
        int pages = count == 0 ? 0 : (int) ((count + size - 1) / size);
        return new PayPalWebhookExhaustedRetryPage(items, page, size, count, pages);
    }

    public PayPalWebhookRequeueResponse requeue(String eventId, String idempotencyKey, String actor, String reason) {
        validate(idempotencyKey, actor, reason);
        try {
            return transactions.execute(status -> requeueInTransaction(eventId, idempotencyKey, actor, reason));
        } catch (DuplicateKeyException duplicate) {
            return replayOrConflict(eventId, idempotencyKey, actor, reason);
        }
    }

    private PayPalWebhookRequeueResponse requeueInTransaction(String eventId, String key, String actor, String reason) {
        PayPalWebhookRequeueResponse replay = findReplay(eventId, key, actor, reason);
        if (replay != null) return replay;

        if (!events.lockExhausted(eventId)) {
            // 잠금 대기 중 같은 키 요청이 먼저 성공했을 수 있으므로 상태 검사 전에 재생을 확인한다.
            replay = findReplay(eventId, key, actor, reason);
            if (replay != null) return replay;
            boolean exists = events.exists(eventId);
            throw new ResponseStatusException(exists ? HttpStatus.CONFLICT : HttpStatus.NOT_FOUND,
                    exists ? "only EXHAUSTED events can be requeued" : "retry event not found");
        }

        int manualRequeues = events.countRequeues(eventId);
        if (manualRequeues >= MAX_MANUAL_REQUEUES) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "manual requeue limit reached");
        }
        if (!events.requeueExhausted(eventId, manualRequeues + 1, MAX_MANUAL_REQUEUES)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "retry event is no longer EXHAUSTED");
        }
        events.recordRequeue(key, eventId, actor, reason);
        return new PayPalWebhookRequeueResponse(eventId, "REQUEUED");
    }

    private PayPalWebhookRequeueResponse replayOrConflict(String eventId, String key, String actor, String reason) {
        PayPalWebhookRequeueResponse replay = findReplay(eventId, key, actor, reason);
        if (replay != null) return replay;
        throw new ResponseStatusException(HttpStatus.CONFLICT, "Idempotency-Key is already bound to another request");
    }

    private PayPalWebhookRequeueResponse findReplay(String eventId, String key, String actor, String reason) {
        Optional<RequeueRecord> found = events.findRequeueRecord(key);
        if (found.isEmpty()) return null;
        RequeueRecord record = found.get();
        if (!record.eventId().equals(eventId) || !record.actor().equals(actor)
                || !record.reason().equals(reason)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Idempotency-Key is already bound to another request");
        }
        return new PayPalWebhookRequeueResponse(eventId, record.outcome());
    }

    private static void validate(String key, String actor, String reason) {
        if (key == null || key.isBlank() || key.length() > MAX_IDEMPOTENCY_KEY_LENGTH) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Idempotency-Key must contain 1 to 128 characters");
        }
        if (actor == null || actor.isBlank() || actor.length() > MAX_ACTOR_LENGTH) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "operator identity is invalid");
        }
        if (reason == null || reason.isBlank() || reason.length() > MAX_REASON_LENGTH) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "reason must contain 1 to 500 characters");
        }
    }
}
