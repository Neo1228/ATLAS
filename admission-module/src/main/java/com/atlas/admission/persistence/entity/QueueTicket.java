package com.atlas.admission.persistence.entity;

import com.atlas.admission.persistence.state.TicketStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "admission_ticket", uniqueConstraints = {
        @UniqueConstraint(name = "admission_ticket_campaign_sequence_unique", columnNames = {"campaign_id", "sequence"}),
        @UniqueConstraint(name = "admission_ticket_idempotency_unique", columnNames = {"campaign_id", "idempotency_key"})
})
public class QueueTicket {
    @Id
    @Column(name = "ticket_id", nullable = false)
    private UUID ticketId;

    @Column(name = "campaign_id", nullable = false)
    private UUID campaignId;

    @Column(name = "sku_id", nullable = false, length = 128)
    private String skuId;

    @Column(name = "buyer_ref", nullable = false, length = 256)
    private String buyerRef;

    @Column(name = "idempotency_key", nullable = false, length = 256)
    private String idempotencyKey;

    @Column(name = "sequence", nullable = false)
    private long sequence;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 32)
    private TicketStatus status;

    @Column(name = "expires_at")
    private Instant expiresAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "checkout_slot_acquired_at")
    private Instant checkoutSlotAcquiredAt;

    @Column(name = "checkout_slot_held", nullable = false)
    private boolean checkoutSlotHeld;

    protected QueueTicket() { }

    public QueueTicket(UUID ticketId, UUID campaignId, String skuId, String buyerRef, String idempotencyKey,
            long sequence, TicketStatus status, Instant expiresAt, Instant createdAt,
            Instant checkoutSlotAcquiredAt, boolean checkoutSlotHeld) {
        this.ticketId = ticketId;
        this.campaignId = campaignId;
        this.skuId = skuId;
        this.buyerRef = buyerRef;
        this.idempotencyKey = idempotencyKey;
        this.sequence = sequence;
        this.status = status;
        this.expiresAt = expiresAt;
        this.createdAt = createdAt;
        this.checkoutSlotAcquiredAt = checkoutSlotAcquiredAt;
        this.checkoutSlotHeld = checkoutSlotHeld;
    }

    public UUID getTicketId() { return ticketId; }
    public UUID getCampaignId() { return campaignId; }
    public String getSkuId() { return skuId; }
    public String getBuyerRef() { return buyerRef; }
    public String getIdempotencyKey() { return idempotencyKey; }
    public long getSequence() { return sequence; }
    public TicketStatus getStatus() { return status; }
    public Instant getExpiresAt() { return expiresAt; }
    public boolean isCheckoutSlotHeld() { return checkoutSlotHeld; }
    public void setStatus(TicketStatus status) { this.status = status; }
    public void setExpiresAt(Instant expiresAt) { this.expiresAt = expiresAt; }
    public void setCheckoutSlotHeld(boolean checkoutSlotHeld) { this.checkoutSlotHeld = checkoutSlotHeld; }
}
