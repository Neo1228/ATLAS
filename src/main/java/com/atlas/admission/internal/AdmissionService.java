package com.atlas.admission.internal;

import com.atlas.admission.AdmissionApi;
import com.atlas.admission.CheckoutSlotReleaseReason;
import com.atlas.admission.CheckoutSlotUnavailableException;
import com.atlas.admission.IdempotencyPayloadMismatchException;
import com.atlas.admission.JoinQueueCommand;
import com.atlas.admission.QueueTicketView;
import com.atlas.admission.TicketStatus;

import com.atlas.campaign.CampaignApi;
import com.atlas.campaign.CampaignView;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AdmissionService implements AdmissionApi {


    private static final String TICKET_COLUMNS = "ticket_id, campaign_id, sku_id, buyer_ref, sequence, status, expires_at, checkout_slot_held";
    private final JdbcTemplate jdbc;
    private final CampaignApi campaigns;
    private final Clock clock;
    private final Supplier<UUID> ids;

    @Autowired
    public AdmissionService(JdbcTemplate jdbc, CampaignApi campaigns, Clock clock) {
        this(jdbc, campaigns, clock, UUID::randomUUID);
    }

    AdmissionService(JdbcTemplate jdbc, CampaignApi campaigns, Clock clock, Supplier<UUID> ids) {
        this.jdbc = jdbc;
        this.campaigns = campaigns;
        this.clock = clock;
        this.ids = ids;
    }

    @Override
    @Transactional
    public QueueTicketView join(JoinQueueCommand command) {
        if (command == null || command.campaignId() == null || blank(command.skuId())
                || command.skuId().length() > 128 || blank(command.buyerRef()) || command.buyerRef().length() > 256
                || blank(command.idempotencyKey()) || command.idempotencyKey().length() > 256) {
            throw new IllegalArgumentException("Invalid queue join command");
        }
        QueueTicketView prior = findByKey(command.campaignId(), command.idempotencyKey());
        if (prior != null) {
            lockCounter(command.campaignId());
            prior = findByKey(command.campaignId(), command.idempotencyKey());
            if (prior != null) {
                requireSamePayload(prior, command);
                expireDueTicketsForCampaign(command.campaignId(), clock.instant());
                return requireTicket(command.campaignId(), prior.ticketId());
            }
        }
        CampaignView campaign = campaigns.requireOpen(command.campaignId(), command.skuId());
        jdbc.update("INSERT INTO admission_counter (campaign_id, last_sequence) VALUES (?, 0) ON CONFLICT (campaign_id) DO NOTHING",
                command.campaignId());
        lockCounter(command.campaignId());
        Instant now = clock.instant();
        expireDueTicketsForCampaign(command.campaignId(), now);
        // The counter-row lock serializes concurrent first-use requests and FIFO allocation.
        prior = findByKey(command.campaignId(), command.idempotencyKey());
        if (prior != null) {
            requireSamePayload(prior, command);
            return requireTicket(command.campaignId(), prior.ticketId());
        }
        long sequence = jdbc.queryForObject(
                "UPDATE admission_counter SET last_sequence = last_sequence + 1 WHERE campaign_id = ? RETURNING last_sequence",
                Long.class, command.campaignId());
        long active = jdbc.queryForObject(
                "SELECT count(*) FROM admission_ticket WHERE campaign_id = ? AND checkout_slot_held",
                Long.class, command.campaignId());
        boolean slot = active < campaign.maxActiveCheckouts();
        UUID ticketId = ids.get();
        Instant expiresAt = now.plus(campaign.queueTicketTtl());
        jdbc.update("""
                INSERT INTO admission_ticket
                    (ticket_id, campaign_id, sku_id, buyer_ref, idempotency_key, sequence, status, expires_at, created_at, checkout_slot_held, checkout_slot_acquired_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, CASE WHEN ? THEN CAST(? AS TIMESTAMPTZ) ELSE NULL END)
                """, ticketId, command.campaignId(), command.skuId(), command.buyerRef(), command.idempotencyKey(),
                sequence, slot ? TicketStatus.CHECKOUT_ACTIVE.name() : TicketStatus.WAITING.name(),
                Timestamp.from(expiresAt), Timestamp.from(now),
                slot, slot, Timestamp.from(now));
        return requireTicket(command.campaignId(), ticketId);
    }
    @Override
    @Transactional
    public QueueTicketView find(UUID campaignId, UUID ticketId) {
        if (!counterExists(campaignId)) return null;
        lockCounter(campaignId);
        expireDueTicketsForCampaign(campaignId, clock.instant());
        List<QueueTicketView> rows = jdbc.query(
                "SELECT " + TICKET_COLUMNS + " FROM admission_ticket WHERE campaign_id = ? AND ticket_id = ?",
                (rs, n) -> map(rs), campaignId, ticketId);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    @Override
    @Transactional
    public QueueTicketView requireCheckoutSlot(UUID campaignId, UUID ticketId) {
        lockCounter(campaignId);
        expireDueTicketsForCampaign(campaignId, clock.instant());
        QueueTicketView ticket = requireTicket(campaignId, ticketId);
        if (!ticket.checkoutSlotHeld()
                || (ticket.status() != TicketStatus.CHECKOUT_ACTIVE && ticket.status() != TicketStatus.CHECKOUT_STARTED)) {
            throw new CheckoutSlotUnavailableException(campaignId, ticketId);
        }
        return ticket;
    }
    @Override
    @Transactional
    public QueueTicketView startCheckout(UUID campaignId, UUID ticketId) {
        lockCounter(campaignId);
        expireDueTicketsForCampaign(campaignId, clock.instant());
        QueueTicketView ticket = requireTicket(campaignId, ticketId);
        if (ticket.status() == TicketStatus.CHECKOUT_ACTIVE && ticket.checkoutSlotHeld()) {
            jdbc.update("""
                    UPDATE admission_ticket
                    SET status = 'CHECKOUT_STARTED', expires_at = NULL
                    WHERE campaign_id = ? AND ticket_id = ?
                    """, campaignId, ticketId);
        } else if (!(ticket.status() == TicketStatus.CHECKOUT_STARTED && ticket.checkoutSlotHeld())) {
            throw new CheckoutSlotUnavailableException(campaignId, ticketId);
        }
        return requireTicket(campaignId, ticketId);
    }
    @Override
    @Transactional
    public QueueTicketView releaseCheckoutSlot(UUID campaignId, UUID ticketId, CheckoutSlotReleaseReason reason) {
        if (reason == null) throw new IllegalArgumentException("Release reason is required");
        lockCounter(campaignId);
        QueueTicketView ticket = requireTicket(campaignId, ticketId);
        if (!ticket.checkoutSlotHeld()) return ticket;
        TicketStatus status = switch (reason) {
            case INITIAL_AUTHORIZE_SUBMITTED -> TicketStatus.CHECKOUT_SUBMITTED;
            case CHECKOUT_WINDOW_EXPIRED -> TicketStatus.EXPIRED;
            case INVENTORY_UNAVAILABLE, BUYER_CANCELLED -> TicketStatus.CHECKOUT_FAILED;
        };
        jdbc.update("""
                UPDATE admission_ticket
                SET status = ?, checkout_slot_held = false, expires_at = NULL
                WHERE campaign_id = ? AND ticket_id = ?
                """, status.name(), campaignId, ticketId);
        promote(campaignId);
        return requireTicket(campaignId, ticketId);
    }

    @Transactional
    int expireDueTickets() {
        Instant now = clock.instant();
        List<UUID> campaignsToProcess = jdbc.query("""
                SELECT DISTINCT campaign_id FROM admission_ticket
                WHERE status IN ('WAITING', 'CHECKOUT_ACTIVE') AND expires_at <= ?
                ORDER BY campaign_id
                """, (rs, n) -> rs.getObject(1, UUID.class), Timestamp.from(now));
        int expired = 0;
        for (UUID campaignId : campaignsToProcess) {
            lockCounter(campaignId);
            expired += expireDueTicketsForCampaign(campaignId, now);
        }
        return expired;
    }

    private int expireDueTicketsForCampaign(UUID campaignId, Instant now) {
        int expired = jdbc.update("""
                UPDATE admission_ticket SET status = 'EXPIRED', checkout_slot_held = false
                WHERE campaign_id = ? AND status IN ('WAITING', 'CHECKOUT_ACTIVE') AND expires_at <= ?
                """, campaignId, Timestamp.from(now));
        if (expired > 0) promote(campaignId);
        return expired;
    }
    private QueueTicketView findByKey(UUID campaignId, String key) {
        List<QueueTicketView> rows = jdbc.query("SELECT " + TICKET_COLUMNS + " FROM admission_ticket WHERE campaign_id = ? AND idempotency_key = ?",
                (rs, n) -> map(rs), campaignId, key);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    private QueueTicketView requireSamePayload(QueueTicketView prior, JoinQueueCommand command) {
        List<List<String>> payload = jdbc.query("SELECT sku_id, buyer_ref FROM admission_ticket WHERE campaign_id = ? AND ticket_id = ?",
                (rs, n) -> List.of(rs.getString(1), rs.getString(2)), command.campaignId(), prior.ticketId());
        if (payload.isEmpty() || !payload.getFirst().getFirst().equals(command.skuId())
                || !payload.getFirst().get(1).equals(command.buyerRef())) {
            throw new IdempotencyPayloadMismatchException(command.campaignId(), command.idempotencyKey());
        }
        return prior;
    }

    private void lockCounter(UUID campaignId) {
        List<Long> rows = jdbc.query("SELECT last_sequence FROM admission_counter WHERE campaign_id = ? FOR UPDATE",
                (rs, n) -> rs.getLong(1), campaignId);
        if (rows.isEmpty()) throw new IllegalArgumentException("Unknown campaign: " + campaignId);
    }
    private boolean counterExists(UUID campaignId) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM admission_counter WHERE campaign_id = ?)",
                Boolean.class, campaignId));
    }

    private QueueTicketView requireTicket(UUID campaignId, UUID ticketId) {
        QueueTicketView ticket = find(campaignId, ticketId);
        if (ticket == null) throw new IllegalArgumentException("Unknown ticket: " + ticketId);
        return ticket;
    }

    private void promote(UUID campaignId) {
        CampaignView campaign = campaigns.find(campaignId);
        if (campaign == null) return;
        long active = jdbc.queryForObject("SELECT count(*) FROM admission_ticket WHERE campaign_id = ? AND checkout_slot_held", Long.class, campaignId);
        int slots = campaign.maxActiveCheckouts() - Math.toIntExact(active);
        if (slots <= 0) return;
        Instant now = clock.instant();
        List<UUID> waiting = jdbc.query("""
                SELECT ticket_id FROM admission_ticket WHERE campaign_id = ? AND status = 'WAITING' AND expires_at > ?
                ORDER BY sequence LIMIT ? FOR UPDATE
                """, (rs, n) -> rs.getObject(1, UUID.class), campaignId, Timestamp.from(now), slots);
        for (UUID ticketId : waiting) {
            jdbc.update("UPDATE admission_ticket SET status = 'CHECKOUT_ACTIVE', checkout_slot_held = true, checkout_slot_acquired_at = ? WHERE campaign_id = ? AND ticket_id = ? AND status = 'WAITING'",
                    Timestamp.from(now), campaignId, ticketId);
        }
    }

    private static QueueTicketView map(ResultSet rs) throws SQLException {
        Timestamp expiresAt = rs.getTimestamp("expires_at");
        return new QueueTicketView(rs.getObject("ticket_id", UUID.class), rs.getObject("campaign_id", UUID.class),
                rs.getString("sku_id"), rs.getString("buyer_ref"), rs.getLong("sequence"), TicketStatus.valueOf(rs.getString("status")),
                expiresAt == null ? null : expiresAt.toInstant(), rs.getBoolean("checkout_slot_held"));
    }

    private static boolean blank(String value) { return value == null || value.isBlank(); }
}
