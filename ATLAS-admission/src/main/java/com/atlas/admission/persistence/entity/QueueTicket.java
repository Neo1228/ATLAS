package com.atlas.admission.persistence.entity;

import com.atlas.admission.persistence.state.TicketStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.UuidGenerator;

@Entity
@Table(name = "admission_ticket", uniqueConstraints = {
        @UniqueConstraint(name = "admission_ticket_seller_sku_sequence_unique", columnNames = {"seller_id", "sku_id", "sequence"}),
        @UniqueConstraint(name = "admission_ticket_idempotency_unique", columnNames = {"campaign_id", "idempotency_key"})
})
public class QueueTicket {
    @Id
    @Column(name = "ticket_id", nullable = false)
    @GeneratedValue
    @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
    private UUID ticketId;

    @Column(name = "campaign_id", nullable = false)
    private UUID campaignId;

    @Column(name = "seller_id", nullable = false)
    private UUID sellerId;

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

    public QueueTicket(UUID campaignId, UUID sellerId, String skuId, String buyerRef, String idempotencyKey,
            long sequence, TicketStatus status, Instant expiresAt, Instant createdAt,
            Instant checkoutSlotAcquiredAt, boolean checkoutSlotHeld) {
        this.campaignId = campaignId;
        this.sellerId = sellerId;
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
    public UUID getSellerId() { return sellerId; }
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
