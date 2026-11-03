package com.atlas.admission.repository;

import jakarta.persistence.EntityManager;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcQueueRepository {
    private final JdbcTemplate jdbc;
    private final EntityManager entityManager;

    public JdbcQueueRepository(JdbcTemplate jdbc, EntityManager entityManager) {
        this.jdbc = jdbc;
        this.entityManager = entityManager;
    }

    public void createCounterIfAbsent(UUID sellerId, String skuId) {
        jdbc.update("INSERT INTO admission_counter (seller_id, sku_id, last_sequence) VALUES (?, ?, 0) ON CONFLICT (seller_id, sku_id) DO NOTHING", sellerId, skuId);
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

    public int expireDueTicketsForSellerSku(UUID sellerId, String skuId, Instant now) {
        entityManager.flush();
        int changed = jdbc.update("UPDATE admission_ticket SET status = 'EXPIRED', checkout_slot_held = false WHERE seller_id = ? AND sku_id = ? AND status IN ('WAITING', 'CHECKOUT_ACTIVE') AND expires_at <= ?", sellerId, skuId, Timestamp.from(now));
        entityManager.clear();
        return changed;
    }

    public List<UUID> findWaitingTicketIdsForPromotion(UUID sellerId, String skuId, Instant now, int limit) {
        entityManager.flush();
        return jdbc.query("SELECT ticket_id FROM admission_ticket WHERE seller_id = ? AND sku_id = ? AND status = 'WAITING' AND expires_at > ? ORDER BY sequence LIMIT ? FOR UPDATE", (rs, rowNum) -> rs.getObject(1, UUID.class), sellerId, skuId, Timestamp.from(now), limit);
    }

    public void activateWaitingTicket(UUID ticketId, Instant acquiredAt) {
        entityManager.flush();
        jdbc.update("UPDATE admission_ticket SET status = 'CHECKOUT_ACTIVE', checkout_slot_held = true, checkout_slot_acquired_at = ? WHERE ticket_id = ? AND status = 'WAITING'", Timestamp.from(acquiredAt), ticketId);
        entityManager.clear();
    }
}
