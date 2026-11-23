package com.atlas.application.service;

import com.atlas.payment.provider.PaymentWebhookEvent;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.IntUnaryOperator;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** 검증된 PayPal 이벤트의 재처리에 필요한 최소 데이터만 저장합니다. */
@Repository
public class PayPalWebhookRetryRepository {
    private static final int MAX_ATTEMPTS = 8;
    private static final int REAPER_BATCH_SIZE = MAX_ATTEMPTS;
    private static final int LEASE_SECONDS = 60;
    private final JdbcTemplate jdbc;

    public PayPalWebhookRetryRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void enqueue(PaymentWebhookEvent event) {
        jdbc.update("""
                INSERT INTO paypal_webhook_retry
                    (event_id, event_type, resource_id, resource_status, provider_order_id,
                     related_capture_id, resource_amount, resource_currency, attempt_count,
                     state, next_attempt_at, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, 0, 'PENDING', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                ON CONFLICT (event_id) DO NOTHING
                """, event.eventId(), event.eventType(), event.resourceId(), event.resourceStatus(),
                event.providerOrderId(), event.relatedCaptureId(), event.resourceAmount(), event.resourceCurrency());
    }

    /**
     * 새 lease token으로 최대 한 행을 claim합니다. Queue 전이, 만료 lease 종결, 새 이력 기록은
     * 하나의 SQL 문에서 원자적으로 처리합니다.
     */
    public List<ClaimedRetry> claimDue() {
        return pollDue().claimed();
    }

    public PollResult pollDue() {
        // 마지막 시도의 worker가 중단되면 다시 claim할 수 없으므로 lease 만료 시 이력을 종결하고
        // queue 행을 EXHAUSTED로 전환합니다. 마이그레이션 이전 lease에는 기존 이력이 없습니다.
        int reapedCount = jdbc.queryForObject("""
                WITH expired AS MATERIALIZED (
                    SELECT event_id, attempt_count, lease_owner
                    FROM paypal_webhook_retry
                    -- 8 is the schema ceiling (V31 CHECK constraint) and matches MAX_ATTEMPTS; the
                    -- literal keeps the partial reaper index predicate provable to the planner.
                    WHERE state = 'PENDING' AND attempt_count = 8
                      AND (lease_until IS NULL OR lease_until <= clock_timestamp())
                    ORDER BY lease_until, event_id
                    LIMIT ?
                    FOR UPDATE SKIP LOCKED
                ), finalized AS (
                    UPDATE paypal_webhook_retry_attempt_history h
                    SET outcome = 'LEASE_EXPIRED', finished_at = clock_timestamp()
                    FROM expired e
                    WHERE h.lease_token = e.lease_owner AND h.event_id = e.event_id
                      AND h.attempt_number = e.attempt_count AND h.outcome = 'CLAIMED'
                    RETURNING h.lease_token
                ), exhausted AS (
                    UPDATE paypal_webhook_retry r
                    SET state = 'EXHAUSTED',
                        last_error = COALESCE(r.last_error, 'Retry lease expired at attempt limit'),
                        finished_at = COALESCE(r.finished_at, clock_timestamp()),
                        lease_owner = NULL, lease_until = NULL
                    FROM expired e
                    WHERE r.event_id = e.event_id
                      AND (e.lease_owner IS NULL
                           OR EXISTS (SELECT 1 FROM finalized f WHERE f.lease_token = e.lease_owner)
                           OR NOT EXISTS (SELECT 1 FROM paypal_webhook_retry_attempt_history h
                                          WHERE h.lease_token = e.lease_owner AND h.event_id = e.event_id
                                            AND h.attempt_number = e.attempt_count))
                    RETURNING r.event_id
                ) SELECT count(*) FROM exhausted
                """, Integer.class, REAPER_BATCH_SIZE);

        // batch 크기 1은 의도적입니다. 여러 행을 claim하도록 바꾸면 batch UUID를 공유하지 말고
        // 각 행마다 별도의 lease token을 생성해야 합니다.
        String token = UUID.randomUUID().toString();
        List<ClaimedRetry> claimed = jdbc.query("""
                WITH due AS MATERIALIZED (
                    SELECT event_id, attempt_count, lease_owner
                    FROM paypal_webhook_retry
                    WHERE state = 'PENDING' AND attempt_count < ?
                      AND next_attempt_at <= clock_timestamp()
                      AND (lease_until IS NULL OR lease_until <= clock_timestamp())
                    ORDER BY next_attempt_at, created_at, event_id
                    LIMIT 1 FOR UPDATE SKIP LOCKED
                ), finalized AS (
                    UPDATE paypal_webhook_retry_attempt_history h
                    SET outcome = 'LEASE_EXPIRED', finished_at = clock_timestamp()
                    FROM due d
                    WHERE h.lease_token = d.lease_owner AND h.event_id = d.event_id
                      AND h.attempt_number = d.attempt_count AND h.outcome = 'CLAIMED'
                    RETURNING h.lease_token
                ), claimed AS (
                    UPDATE paypal_webhook_retry r
                    SET lease_owner = ?, lease_until = clock_timestamp() + (? * INTERVAL '1 second'),
                        attempt_count = r.attempt_count + 1
                    FROM due d
                    WHERE r.event_id = d.event_id
                      AND (d.lease_owner IS NULL
                           OR EXISTS (SELECT 1 FROM finalized f WHERE f.lease_token = d.lease_owner)
                           OR NOT EXISTS (SELECT 1 FROM paypal_webhook_retry_attempt_history h
                                          WHERE h.lease_token = d.lease_owner AND h.event_id = d.event_id
                                            AND h.attempt_number = d.attempt_count))
                    RETURNING r.event_id, r.event_type, r.resource_id, r.resource_status,
                              r.provider_order_id, r.related_capture_id, r.resource_amount,
                              r.resource_currency, r.attempt_count, r.lease_owner
                ), history AS (
                    INSERT INTO paypal_webhook_retry_attempt_history
                        (event_id, attempt_number, lease_token, outcome, claimed_at)
                    SELECT event_id, attempt_count, lease_owner, 'CLAIMED', clock_timestamp() FROM claimed
                    RETURNING lease_token
                )
                SELECT c.* FROM claimed c JOIN history h ON h.lease_token = c.lease_owner
                """, this::mapClaim, MAX_ATTEMPTS, token, LEASE_SECONDS);
        return new PollResult(claimed, reapedCount);
    }

    private ClaimedRetry mapClaim(ResultSet rs, int rowNum) throws SQLException {
        BigDecimal amount = rs.getBigDecimal("resource_amount");
        PaymentWebhookEvent event = new PaymentWebhookEvent(rs.getString("event_id"), rs.getString("event_type"),
                rs.getString("resource_id"), rs.getString("resource_status"), rs.getString("provider_order_id"),
                rs.getString("related_capture_id"), amount, rs.getString("resource_currency"));
        return new ClaimedRetry(event, rs.getInt("attempt_count"), rs.getString("lease_owner"));
    }

    /** lease가 오래되었거나 만료·누락 또는 이미 종결된 경우 false를 반환합니다. */
    public boolean complete(String eventId, String leaseOwner) {
        Integer changed = jdbc.queryForObject("""
                WITH locked AS MATERIALIZED (
                    SELECT event_id, state, attempt_count, lease_owner, lease_until
                    FROM paypal_webhook_retry WHERE event_id = ? FOR UPDATE
                ), eligible AS MATERIALIZED (
                    SELECT * FROM locked WHERE state = 'PENDING' AND lease_owner = ?
                      AND lease_until > clock_timestamp()
                ), finalized AS (
                    UPDATE paypal_webhook_retry_attempt_history h
                    SET outcome = 'PROCESSED', finished_at = clock_timestamp()
                    FROM eligible e
                    WHERE h.lease_token = e.lease_owner AND h.event_id = e.event_id
                      AND h.attempt_number = e.attempt_count AND h.outcome = 'CLAIMED'
                    RETURNING h.lease_token
                ), completed AS (
                    DELETE FROM paypal_webhook_retry r USING eligible e
                    WHERE r.event_id = e.event_id
                      AND (EXISTS (SELECT 1 FROM finalized f WHERE f.lease_token = e.lease_owner)
                           OR NOT EXISTS (SELECT 1 FROM paypal_webhook_retry_attempt_history h
                                          WHERE h.lease_token = e.lease_owner AND h.event_id = e.event_id
                                            AND h.attempt_number = e.attempt_count))
                    RETURNING r.event_id
                )
                SELECT COUNT(*) FROM completed
                """, Integer.class, eventId, leaseOwner);
        return changed != null && changed == 1;
    }

    public enum RetryFailure { CORRELATION_MISS, TRANSIENT_ERROR }

    /** 일시적 실패를 재예약하고 시도 한도에 도달하면 EXHAUSTED 이력을 기록합니다. */
    public boolean reschedule(String eventId, int attempt, String leaseOwner, RetryFailure failure, String reason) {
        Objects.requireNonNull(failure, "failure");
        int delaySeconds = jitteredDelaySeconds(baseDelaySeconds(attempt), ThreadLocalRandom.current()::nextInt);
        return finishFailure(eventId, attempt, leaseOwner, failure.name(), reason, false, delaySeconds);
    }

    /** 시도 횟수에 따라 두 배씩 늘어나는 기본 지연(초)이다. 상한은 300초다. */
    static int baseDelaySeconds(int attempt) {
        return Math.min(300, 1 << Math.min(Math.max(attempt - 1, 0), 8));
    }

    /**
     * 같은 시각에 실패한 이벤트가 같은 시각에 다시 시도되면 공급자가 복구되는 순간 요청이 한꺼번에
     * 몰린다. 지연을 [기본/2, 기본] 초 사이로 흩뜨려 재시도 시각을 분산한다. 최소 1초는 보장한다.
     *
     * @param randomBelow 0 이상 인자 미만의 정수를 돌려주는 난수 공급자
     */
    static int jitteredDelaySeconds(int baseSeconds, IntUnaryOperator randomBelow) {
        int floor = Math.max(1, baseSeconds / 2);
        return floor + randomBelow.applyAsInt(Math.max(1, baseSeconds - floor + 1));
    }

    /** 영구 거부 결과를 기록하며 다음 시도를 예약하지 않습니다. */
    public boolean reject(String eventId, int attempt, String leaseOwner, String reason) {
        return finishFailure(eventId, attempt, leaseOwner, "PERMANENT_REJECT", reason, true, 0);
    }

    private boolean finishFailure(String eventId, int attempt, String leaseOwner, String outcome, String reason,
                                  boolean rejected, int delaySeconds) {
        String error = reason == null ? "Unknown retry failure" : reason.substring(0, Math.min(1000, reason.length()));
        Integer changed = jdbc.queryForObject("""
                WITH locked AS MATERIALIZED (
                    SELECT event_id, state, attempt_count, lease_owner, lease_until
                    FROM paypal_webhook_retry WHERE event_id = ? FOR UPDATE
                ), eligible AS MATERIALIZED (
                    SELECT * FROM locked WHERE state = 'PENDING' AND attempt_count = ?
                      AND lease_owner = ? AND lease_until > clock_timestamp()
                ), finalized AS (
                    UPDATE paypal_webhook_retry_attempt_history h
                    SET outcome = CASE WHEN ? THEN 'PERMANENT_REJECT'
                                       WHEN e.attempt_count >= ? THEN 'EXHAUSTED' ELSE ? END,
                        finished_at = clock_timestamp()
                    FROM eligible e
                    WHERE h.lease_token = e.lease_owner AND h.event_id = e.event_id
                      AND h.attempt_number = e.attempt_count AND h.outcome = 'CLAIMED'
                    RETURNING h.lease_token
                ), failed AS (
                    UPDATE paypal_webhook_retry r
                    SET state = CASE WHEN ? OR e.attempt_count >= ? THEN 'EXHAUSTED' ELSE 'PENDING' END,
                        next_attempt_at = CASE WHEN ? OR e.attempt_count >= ? THEN r.next_attempt_at
                                               ELSE clock_timestamp() + (? * INTERVAL '1 second') END,
                        finished_at = CASE WHEN ? OR e.attempt_count >= ? THEN clock_timestamp() ELSE NULL END,
                        last_error = ?, lease_owner = NULL, lease_until = NULL
                    FROM eligible e
                    WHERE r.event_id = e.event_id
                      AND (EXISTS (SELECT 1 FROM finalized f WHERE f.lease_token = e.lease_owner)
                           OR NOT EXISTS (SELECT 1 FROM paypal_webhook_retry_attempt_history h
                                          WHERE h.lease_token = e.lease_owner AND h.event_id = e.event_id
                                            AND h.attempt_number = e.attempt_count))
                    RETURNING r.event_id
                )
                SELECT COUNT(*) FROM failed
                """, Integer.class, eventId, attempt, leaseOwner, rejected, MAX_ATTEMPTS, outcome,
                rejected, MAX_ATTEMPTS, rejected, MAX_ATTEMPTS, delaySeconds,
                rejected, MAX_ATTEMPTS, error);
        return changed != null && changed == 1;
    }

    /**
     * 대기 중인 재시도 중 가장 오래된 항목의 경과 시간(초). 큐가 비어 있으면 null.
     *
     * Issue #337: worker가 claim·outcome·duration은 노출했지만 밀리고 있는지는
     * 알 수 없었다. 그래서 운영자가 "할 일 없음"과 "적체"를 구분하지 못했다.
     * 경과 시간은 가장 이른 {@code next_attempt_at} 기준이며, 가장 밀린 항목이
     * 자기 예정 시각을 얼마나 넘겼는지를 뜻한다.
     */
    public Double oldestPendingAgeSeconds() {
        return jdbc.queryForObject("""
                SELECT EXTRACT(EPOCH FROM (clock_timestamp() - MIN(next_attempt_at)))
                FROM paypal_webhook_retry
                WHERE state = 'PENDING' AND attempt_count < ?
                """, Double.class, MAX_ATTEMPTS);
    }

    public record PollResult(List<ClaimedRetry> claimed, int reapedCount) { }

    public record ClaimedRetry(PaymentWebhookEvent event, int attempt, String leaseOwner) { }
}
