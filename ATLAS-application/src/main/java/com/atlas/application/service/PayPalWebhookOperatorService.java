package com.atlas.application.service;

import com.atlas.application.dto.response.PayPalWebhookExhaustedRetryPage;
import com.atlas.application.dto.response.PayPalWebhookExhaustedRetryPage.Item;
import com.atlas.application.dto.response.PayPalWebhookRequeueResponse;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

@Service
public class PayPalWebhookOperatorService {
    public static final int DEFAULT_PAGE_SIZE = 20;
    public static final int MAX_PAGE_SIZE = 100;
    public static final long MAX_PAGE_OFFSET = 10_000;
    public static final int MAX_MANUAL_REQUEUES = 3;
    private static final int MAX_REASON_LENGTH = 500;
    private static final int MAX_IDEMPOTENCY_KEY_LENGTH = 128;
    private static final int MAX_ACTOR_LENGTH = 128;

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;

    public PayPalWebhookOperatorService(JdbcTemplate jdbc, PlatformTransactionManager transactionManager) {
        this.jdbc = Objects.requireNonNull(jdbc);
        this.transactions = new TransactionTemplate(Objects.requireNonNull(transactionManager));
    }

    public PayPalWebhookExhaustedRetryPage listExhausted(int page, int requestedSize) {
        if (page < 0) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "page must be non-negative");
        int size = requestedSize <= 0 ? DEFAULT_PAGE_SIZE : Math.min(requestedSize, MAX_PAGE_SIZE);
        long offset = (long) page * size;
        if (offset > MAX_PAGE_OFFSET) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "page offset is too large");
        }
        Long total = jdbc.queryForObject("SELECT COUNT(*) FROM paypal_webhook_retry WHERE state = 'EXHAUSTED'", Long.class);
        List<Item> items = jdbc.query("""
                SELECT event_id, event_type, attempt_count, created_at, finished_at
                FROM paypal_webhook_retry
                WHERE state = 'EXHAUSTED'
                ORDER BY finished_at DESC, event_id ASC
                LIMIT ? OFFSET ?
                """, (rs, rowNum) -> mapItem(rs), size, offset);
        long count = total == null ? 0 : total;
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

        List<String> exhausted = jdbc.query("""
                SELECT event_id
                FROM paypal_webhook_retry
                WHERE event_id = ? AND state = 'EXHAUSTED'
                FOR UPDATE
                """, (rs, rowNum) -> rs.getString(1), eventId);
        if (exhausted.isEmpty()) {
            // 잠금 대기 중 같은 키 요청이 먼저 성공했을 수 있으므로 상태 검사 전에 재생을 확인한다.
            replay = findReplay(eventId, key, actor, reason);
            if (replay != null) return replay;
            boolean exists = Boolean.TRUE.equals(jdbc.queryForObject(
                    "SELECT EXISTS (SELECT 1 FROM paypal_webhook_retry WHERE event_id = ?)", Boolean.class, eventId));
            throw new ResponseStatusException(exists ? HttpStatus.CONFLICT : HttpStatus.NOT_FOUND,
                    exists ? "only EXHAUSTED events can be requeued" : "retry event not found");
        }

        Integer priorRequeues = jdbc.queryForObject(
                "SELECT COUNT(*) FROM paypal_webhook_retry_operator_audit WHERE event_id = ?", Integer.class, eventId);
        int manualRequeues = priorRequeues == null ? 0 : priorRequeues;
        if (manualRequeues >= MAX_MANUAL_REQUEUES) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "manual requeue limit reached");
        }

        int updated = jdbc.update("""
                UPDATE paypal_webhook_retry
                SET state = 'PENDING', attempt_count = 0, next_attempt_at = CURRENT_TIMESTAMP,
                    lease_owner = NULL, lease_until = NULL, last_error = NULL, finished_at = NULL,
                    manual_requeue_count = ?
                WHERE event_id = ? AND state = 'EXHAUSTED' AND manual_requeue_count < ?
                """, manualRequeues + 1, eventId, MAX_MANUAL_REQUEUES);
        if (updated != 1) throw new ResponseStatusException(HttpStatus.CONFLICT, "retry event is no longer EXHAUSTED");

        jdbc.update("""
                INSERT INTO paypal_webhook_retry_operator_audit
                    (idempotency_key, event_id, actor, reason, outcome)
                VALUES (?, ?, ?, ?, 'REQUEUED')
                """, key, eventId, actor, reason);
        return new PayPalWebhookRequeueResponse(eventId, "REQUEUED");
    }

    private PayPalWebhookRequeueResponse replayOrConflict(String eventId, String key, String actor, String reason) {
        PayPalWebhookRequeueResponse replay = findReplay(eventId, key, actor, reason);
        if (replay != null) return replay;
        throw new ResponseStatusException(HttpStatus.CONFLICT, "Idempotency-Key is already bound to another request");
    }

    private PayPalWebhookRequeueResponse findReplay(String eventId, String key, String actor, String reason) {
        List<String[]> records = jdbc.query("""
                SELECT event_id, actor, reason, outcome
                FROM paypal_webhook_retry_operator_audit
                WHERE idempotency_key = ?
                """, (rs, rowNum) -> new String[] {
                rs.getString("event_id"), rs.getString("actor"), rs.getString("reason"), rs.getString("outcome")
        }, key);
        if (records.isEmpty()) return null;
        String[] record = records.get(0);
        if (!record[0].equals(eventId) || !record[1].equals(actor) || !record[2].equals(reason)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Idempotency-Key is already bound to another request");
        }
        return new PayPalWebhookRequeueResponse(eventId, record[3]);
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

    private static Item mapItem(ResultSet rs) throws SQLException {
        return new Item(rs.getString("event_id"), rs.getString("event_type"), rs.getInt("attempt_count"),
                instant(rs, "created_at"), instant(rs, "finished_at"));
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        var value = rs.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }
}
