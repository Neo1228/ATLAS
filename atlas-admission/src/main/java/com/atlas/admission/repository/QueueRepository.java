package com.atlas.admission.repository;

import com.atlas.admission.persistence.projection.DueTicketCandidate;
import com.atlas.admission.persistence.projection.DueTicketCursor;
import jakarta.persistence.EntityManager;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class QueueRepository {
    private final JdbcTemplate jdbc;
    private final EntityManager entityManager;

    public QueueRepository(JdbcTemplate jdbc, EntityManager entityManager) {
        this.jdbc = jdbc;
        this.entityManager = entityManager;
    }

    public void createCounterIfAbsent(UUID sellerId, String skuId) {
        jdbc.update("INSERT INTO admission_counter (seller_id, sku_id, last_sequence) VALUES (?, ?, 0) ON CONFLICT (seller_id, sku_id) DO NOTHING", sellerId, skuId);
    }

    /** Reads a keyset-bounded window of due tickets from the expiry index. */
    public List<DueTicketCandidate> findDueTicketCandidates(
            Instant now, DueTicketCursor after, int limit, Duration queryTimeout) {
        if (limit < 1 || limit > 2_000) {
            throw new IllegalArgumentException("candidate limit must be between 1 and 2000");
        }
        int timeoutSeconds = Math.max(1, Math.toIntExact(queryTimeout.toSeconds()));
        String sql = after == null ? """
                SELECT ticket_id, seller_id, sku_id, expires_at
                FROM admission_ticket
                WHERE status IN ('WAITING', 'CHECKOUT_ACTIVE') AND expires_at <= ?
                ORDER BY expires_at, ticket_id
                LIMIT ?
                """ : """
                SELECT ticket_id, seller_id, sku_id, expires_at
                FROM admission_ticket
                WHERE status IN ('WAITING', 'CHECKOUT_ACTIVE') AND expires_at <= ?
                  AND (expires_at, ticket_id) > (?, ?)
                ORDER BY expires_at, ticket_id
                LIMIT ?
                """;
        return jdbc.query(connection -> {
            var statement = connection.prepareStatement(sql);
            statement.setQueryTimeout(timeoutSeconds);
            statement.setTimestamp(1, Timestamp.from(now));
            if (after == null) {
                statement.setInt(2, limit);
            } else {
                statement.setTimestamp(2, Timestamp.from(after.expiresAt()));
                statement.setObject(3, after.ticketId());
                statement.setInt(4, limit);
            }
            return statement;
        }, (result, rowNumber) -> new DueTicketCandidate(
                result.getObject("ticket_id", UUID.class),
                result.getObject("seller_id", UUID.class),
                result.getString("sku_id"), result.getTimestamp("expires_at").toInstant()));
    }

    public enum CounterLockResult {
        ACQUIRED,
        BUSY,
        MISSING
    }

    /** 다른 scheduler instance의 counter lock을 기다리지 않고 획득을 시도한다. */
    public CounterLockResult tryLockCounter(UUID sellerId, String skuId) {
        entityManager.flush();
        List<Long> rows = jdbc.query("SELECT last_sequence FROM admission_counter "
                        + "WHERE seller_id = ? AND sku_id = ? FOR UPDATE SKIP LOCKED",
                (rs, rowNum) -> rs.getLong(1), sellerId, skuId);
        if (!rows.isEmpty()) {
            entityManager.clear();
            return CounterLockResult.ACQUIRED;
        }
        Boolean exists = jdbc.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM admission_counter WHERE seller_id = ? AND sku_id = ?)",
                Boolean.class, sellerId, skuId);
        entityManager.clear();
        return Boolean.TRUE.equals(exists) ? CounterLockResult.BUSY : CounterLockResult.MISSING;
    }

    public boolean lockCounter(UUID sellerId, String skuId) {
        entityManager.flush();
        List<Long> rows = jdbc.query("SELECT last_sequence FROM admission_counter WHERE seller_id = ? AND sku_id = ? FOR UPDATE", (rs, rowNum) -> rs.getLong(1), sellerId, skuId);
        entityManager.clear();
        return !rows.isEmpty();
    }

    public long incrementSequence(UUID sellerId, String skuId) {
        entityManager.flush();
        Long sequence = jdbc.queryForObject("UPDATE admission_counter SET last_sequence = last_sequence + 1 WHERE seller_id = ? AND sku_id = ? RETURNING last_sequence", Long.class, sellerId, skuId);
        entityManager.clear();
        return sequence;
    }

    /**
     * 한 구매자가 같은 Seller-SKU에서 가진 live ticket 수. 상태 목록을 리터럴로 쓰는 이유는 바인드 파라미터로는
     * planner가 부분 인덱스({@code admission_ticket_live_buyer_idx})의 조건을 증명하지 못하기 때문이다.
     * counter 잠금을 쥔 transaction 안에서만 호출해야 검사와 삽입 사이에 같은 Seller-SKU의 다른 join이 끼어들지 못한다.
     */
    public long countLiveTickets(UUID sellerId, String skuId, String buyerRef) {
        entityManager.flush();
        Long count = jdbc.queryForObject("SELECT count(*) FROM admission_ticket WHERE seller_id = ? AND sku_id = ? "
                + "AND buyer_ref = ? AND status IN ('WAITING', 'CHECKOUT_ACTIVE', 'CHECKOUT_STARTED')",
                Long.class, sellerId, skuId, buyerRef);
        return count == null ? 0 : count;
    }

    /**
     * 주문이 가리키지 않는 오래된 종료 ticket을 지운다. 후보는 {@code admission_ticket_tombstone_idx} 순서(created_at)로
     * 최대 limit개를 읽고(checkout이 시작된 ticket은 인덱스에 없어 건너뛴다. 영영 못 지우는 행이 앞쪽에 쌓여도 읽기가 늘지 않는다), {@code FOR UPDATE SKIP LOCKED}라 다른 인스턴스의 같은 작업을 기다리지 않는다.
     * {@code NOT EXISTS (purchase_order)}는 마이그레이션 전에 시작된
     * checkout까지 지키는 안전망이며 다른 모듈 소유 테이블을 SQL에서만 참조한다(Java 의존 없음, ADR-0088).
     */
    public int purgeTombstones(Instant createdBefore, int limit) {
        if (limit < 1 || limit > 5_000) {
            throw new IllegalArgumentException("purge limit must be between 1 and 5000");
        }
        entityManager.flush();
        int deleted = jdbc.update("""
                DELETE FROM admission_ticket
                WHERE ticket_id IN (
                    SELECT t.ticket_id FROM admission_ticket t
                    WHERE t.status IN ('EXPIRED', 'CHECKOUT_FAILED') AND t.checkout_started_at IS NULL AND t.created_at < ?
                      AND NOT EXISTS (SELECT 1 FROM purchase_order o WHERE o.ticket_id = t.ticket_id)
                    ORDER BY t.created_at, t.ticket_id
                    LIMIT ?
                    FOR UPDATE OF t SKIP LOCKED)
                """, Timestamp.from(createdBefore), limit);
        entityManager.clear();
        return deleted;
    }

    public int expireDueTicketsForSellerSku(UUID sellerId, String skuId, Instant now) {
        entityManager.flush();
        int changed = jdbc.update("UPDATE admission_ticket SET status = 'EXPIRED', checkout_slot_held = false WHERE seller_id = ? AND sku_id = ? AND status IN ('WAITING', 'CHECKOUT_ACTIVE') AND expires_at <= ?", sellerId, skuId, Timestamp.from(now));
        entityManager.clear();
        return changed;
    }

    public int expireDueTicketCandidates(UUID sellerId, String skuId, List<UUID> ticketIds, Instant now) {
        if (ticketIds.isEmpty()) {
            return 0;
        }
        entityManager.flush();
        String placeholders = String.join(", ", Collections.nCopies(ticketIds.size(), "?"));
        Object[] parameters = new Object[ticketIds.size() + 3];
        parameters[0] = sellerId;
        parameters[1] = skuId;
        parameters[2] = Timestamp.from(now);
        System.arraycopy(ticketIds.toArray(), 0, parameters, 3, ticketIds.size());
        int changed = jdbc.update("UPDATE admission_ticket SET status = 'EXPIRED', checkout_slot_held = false WHERE seller_id = ? AND sku_id = ? AND status IN ('WAITING', 'CHECKOUT_ACTIVE') AND expires_at <= ? AND ticket_id IN (" + placeholders + ")", parameters);
        entityManager.clear();
        return changed;
    }

    /**
     * 대기 ticket을 checkout slot 보유 상태로 승격합니다.
     * 미시작 checkout 창은 join 시점이 아니라 slot 획득 시점부터 계산하므로 {@code expires_at}을
     * {@code acquiredAt + checkoutSlotWindow}로 다시 설정합니다. join 즉시 slot을 얻은 ticket과 같은 기준입니다.
     */
    public void activateWaitingTicket(UUID ticketId, Instant acquiredAt, Duration checkoutSlotWindow) {
        entityManager.flush();
        Instant expiresAt = acquiredAt.plus(checkoutSlotWindow);
        jdbc.update("UPDATE admission_ticket SET status = 'CHECKOUT_ACTIVE', checkout_slot_held = true, checkout_slot_acquired_at = ?, expires_at = ? WHERE ticket_id = ? AND status = 'WAITING'",
                Timestamp.from(acquiredAt), Timestamp.from(expiresAt), ticketId);
        entityManager.clear();
    }
}
