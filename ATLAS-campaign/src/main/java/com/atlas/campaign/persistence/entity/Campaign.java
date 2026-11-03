package com.atlas.campaign.persistence.entity;

import com.atlas.campaign.persistence.state.CampaignStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.Getter;
import org.hibernate.annotations.UuidGenerator;

@Entity
@Table(name = "campaign")
public class Campaign {

    @Id
    @Column(name = "campaign_id")
    @GeneratedValue
    @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
    @Getter
    private UUID campaignId;

    @Column(name = "seller_id", nullable = false)
    @Getter
    private UUID sellerId;

    @Column(name = "sku_id", nullable = false, length = 128)
    @Getter
    private String skuId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    @Getter
    private CampaignStatus status;

    @Column(name = "queue_ticket_ttl_seconds", nullable = false)
    @Getter
    private long queueTicketTtlSeconds;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected Campaign() {
    }

 public Campaign(UUID sellerId, String skuId, CampaignStatus status,
 long queueTicketTtlSeconds, Instant createdAt) {
        this.sellerId = sellerId;
        this.skuId = skuId;
        this.status = status;
        this.queueTicketTtlSeconds = queueTicketTtlSeconds;
        this.createdAt = createdAt;
    }

    public void setStatus(CampaignStatus status) {
        this.status = status;
    }
}
