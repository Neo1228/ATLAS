package com.atlas.campaign.persistence.entity;

import com.atlas.campaign.persistence.state.CampaignStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "campaign")
public class Campaign {

    @Id
    @Column(name = "campaign_id")
    private UUID campaignId;

    @Column(name = "seller_id", nullable = false)
    private UUID sellerId;

    @Column(name = "sku_id", nullable = false, length = 128)
    private String skuId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private CampaignStatus status;

    @Column(name = "queue_ticket_ttl_seconds", nullable = false)
    private long queueTicketTtlSeconds;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected Campaign() {
    }

    public Campaign(UUID campaignId, UUID sellerId, String skuId, CampaignStatus status,
            long queueTicketTtlSeconds, Instant createdAt) {
        this.campaignId = campaignId;
        this.sellerId = sellerId;
        this.skuId = skuId;
        this.status = status;
        this.queueTicketTtlSeconds = queueTicketTtlSeconds;
        this.createdAt = createdAt;
    }

    public UUID getCampaignId() {
        return campaignId;
    }

    public UUID getSellerId() {
        return sellerId;
    }

    public String getSkuId() {
        return skuId;
    }

    public CampaignStatus getStatus() {
        return status;
    }

    public long getQueueTicketTtlSeconds() {
        return queueTicketTtlSeconds;
    }

    public void setStatus(CampaignStatus status) {
        this.status = status;
    }
}
