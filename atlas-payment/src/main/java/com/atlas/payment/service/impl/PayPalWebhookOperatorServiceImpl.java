package com.atlas.payment.service.impl;

import com.atlas.payment.dto.response.WebhookRetryResponse;
import com.atlas.payment.dto.response.WebhookRetryResponse.ExhaustedRetryPage.Item;
import com.atlas.payment.exception.PaymentException.Code;
import com.atlas.payment.exception.PaymentException;
import com.atlas.payment.persistence.projection.WebhookRequeueRecord;
import com.atlas.payment.repository.PayPalWebhookOperatorRepository;
import com.atlas.payment.service.PayPalWebhookOperatorService;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class PayPalWebhookOperatorServiceImpl implements PayPalWebhookOperatorService {
    private static final int MAX_REASON_LENGTH = 500;
    private static final int MAX_IDEMPOTENCY_KEY_LENGTH = 128;
    private static final int MAX_ACTOR_LENGTH = 128;

    private final PayPalWebhookOperatorRepository events;
    private final TransactionTemplate transactions;

    public PayPalWebhookOperatorServiceImpl(
            PayPalWebhookOperatorRepository events, PlatformTransactionManager transactionManager) {
        this.events = Objects.requireNonNull(events);
        this.transactions = new TransactionTemplate(Objects.requireNonNull(transactionManager));
    }

    @Override
    public WebhookRetryResponse.ExhaustedRetryPage listExhausted(int page, int requestedSize) {
        if (page < 0) throw new PaymentException(Code.INVALID_REQUEST, "page must be non-negative");
        int size = requestedSize <= 0 ? DEFAULT_PAGE_SIZE : Math.min(requestedSize, MAX_PAGE_SIZE);
        long offset = (long) page * size;
        if (offset > MAX_PAGE_OFFSET) {
            throw new PaymentException(Code.INVALID_REQUEST, "page offset is too large");
        }
        long count = events.countExhausted();
        List<Item> items = events.listExhausted(size, offset).stream()
                .map(row -> new Item(row.eventId(), row.eventType(), row.attemptCount(),
                        row.createdAt(), row.finishedAt()))
                .toList();
        int pages = count == 0 ? 0 : (int) ((count + size - 1) / size);
        return new WebhookRetryResponse.ExhaustedRetryPage(items, page, size, count, pages);
    }

    @Override
    public WebhookRetryResponse.Requeue requeue(String eventId, String idempotencyKey, String actor, String reason) {
        validate(idempotencyKey, actor, reason);
        try {
            return transactions.execute(status -> requeueInTransaction(eventId, idempotencyKey, actor, reason));
        } catch (DuplicateKeyException duplicate) {
            return replayOrConflict(eventId, idempotencyKey, actor, reason);
        }
    }

    private WebhookRetryResponse.Requeue requeueInTransaction(String eventId, String key, String actor, String reason) {
        WebhookRetryResponse.Requeue replay = findReplay(eventId, key, actor, reason);
        if (replay != null) return replay;

        if (!events.lockExhausted(eventId)) {
            // 잠금 대기 중 같은 키 요청이 먼저 성공했을 수 있으므로 상태 검사 전에 재생을 확인한다.
            replay = findReplay(eventId, key, actor, reason);
            if (replay != null) return replay;
            boolean exists = events.exists(eventId);
            throw new PaymentException(exists ? Code.CONFLICT : Code.NOT_FOUND,
                    exists ? "only EXHAUSTED events can be requeued" : "retry event not found");
        }

        int manualRequeues = events.countRequeues(eventId);
        if (manualRequeues >= MAX_MANUAL_REQUEUES) {
            throw new PaymentException(Code.CONFLICT, "manual requeue limit reached");
        }
        if (!events.requeueExhausted(eventId, manualRequeues + 1, MAX_MANUAL_REQUEUES)) {
            throw new PaymentException(Code.CONFLICT, "retry event is no longer EXHAUSTED");
        }
        events.recordRequeue(key, eventId, actor, reason);
        return new WebhookRetryResponse.Requeue(eventId, "REQUEUED");
    }

    private WebhookRetryResponse.Requeue replayOrConflict(String eventId, String key, String actor, String reason) {
        WebhookRetryResponse.Requeue replay = findReplay(eventId, key, actor, reason);
        if (replay != null) return replay;
        throw new PaymentException(Code.CONFLICT, "Idempotency-Key is already bound to another request");
    }

    private WebhookRetryResponse.Requeue findReplay(String eventId, String key, String actor, String reason) {
        Optional<WebhookRequeueRecord> found = events.findRequeueRecord(key);
        if (found.isEmpty()) return null;
        WebhookRequeueRecord record = found.get();
        if (!record.eventId().equals(eventId) || !record.actor().equals(actor)
                || !record.reason().equals(reason)) {
            throw new PaymentException(Code.CONFLICT, "Idempotency-Key is already bound to another request");
        }
        return new WebhookRetryResponse.Requeue(eventId, record.outcome());
    }

    private static void validate(String key, String actor, String reason) {
        if (key == null || key.isBlank() || key.length() > MAX_IDEMPOTENCY_KEY_LENGTH) {
            throw new PaymentException(Code.INVALID_REQUEST, "Idempotency-Key must contain 1 to 128 characters");
        }
        if (actor == null || actor.isBlank() || actor.length() > MAX_ACTOR_LENGTH) {
            throw new PaymentException(Code.INVALID_REQUEST, "operator identity is invalid");
        }
        if (reason == null || reason.isBlank() || reason.length() > MAX_REASON_LENGTH) {
            throw new PaymentException(Code.INVALID_REQUEST, "reason must contain 1 to 500 characters");
        }
    }
}
