package com.atlas.admission.persistence.repository;

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

    public void createCounterIfAbsent(UUID campaignId) {
        jdbc.update("INSERT INTO admission_counter (campaign_id, last_sequence) VALUES (?, 0) ON CONFLICT (campaign_id) DO NOTHING",
                campaignId);
    }

    public boolean lockCounter(UUID campaignId) {
        entityManager.flush();
        List<Long> rows = jdbc.query("SELECT last_sequence FROM admission_counter WHERE campaign_id = ? FOR UPDATE",
                (rs, rowNum) -> rs.getLong(1), campaignId);
        entityManager.clear();
        return !rows.isEmpty();
    }

    public long incrementSequence(UUID campaignId) {
        entityManager.flush();
        Long sequence = jdbc.queryForObject(
                "UPDATE admission_counter SET last_sequence = last_sequence + 1 WHERE campaign_id = ? RETURNING last_sequence",
                Long.class, campaignId);
        entityManager.clear();
        return sequence;
    }


    public int expireDueTicketsForCampaign(UUID campaignId, Instant now) {
        entityManager.flush();
        int changed = jdbc.update("UPDATE admission_ticket SET status = 'EXPIRED', checkout_slot_held = false WHERE campaign_id = ? AND status IN ('WAITING', 'CHECKOUT_ACTIVE') AND expires_at <= ?",
                campaignId, Timestamp.from(now));
        entityManager.clear();
        return changed;
    }

    public List<UUID> findWaitingTicketIdsForPromotion(UUID campaignId, Instant now, int limit) {
        entityManager.flush();
        return jdbc.query("SELECT ticket_id FROM admission_ticket WHERE campaign_id = ? AND status = 'WAITING' AND expires_at > ? ORDER BY sequence LIMIT ? FOR UPDATE",
                (rs, rowNum) -> rs.getObject(1, UUID.class), campaignId, Timestamp.from(now), limit);
    }

    public void activateWaitingTicket(UUID campaignId, UUID ticketId, Instant acquiredAt) {
        entityManager.flush();
        jdbc.update("UPDATE admission_ticket SET status = 'CHECKOUT_ACTIVE', checkout_slot_held = true, checkout_slot_acquired_at = ? WHERE campaign_id = ? AND ticket_id = ? AND status = 'WAITING'",
                Timestamp.from(acquiredAt), campaignId, ticketId);
        entityManager.clear();
    }
}
