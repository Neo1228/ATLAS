package com.atlas.inventory.persistence.entity;

import java.io.Serializable;
import java.util.Objects;
import java.util.UUID;
import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;

@Embeddable
public class PoolId implements Serializable {
    private static final long serialVersionUID = 1L;

    @Column(name = "campaign_id", nullable = false)
    private UUID campaignId;

    @Column(name = "sku_id", nullable = false, length = 128)
    private String skuId;

    protected PoolId() {
    }

    public PoolId(UUID campaignId, String skuId) {
        this.campaignId = campaignId;
        this.skuId = skuId;
    }

    public UUID getCampaignId() { return campaignId; }
    public String getSkuId() { return skuId; }

    @Override
    public boolean equals(Object other) {
        if (this == other) return true;
        if (!(other instanceof PoolId that)) return false;
        return Objects.equals(campaignId, that.campaignId) && Objects.equals(skuId, that.skuId);
    }

    @Override
    public int hashCode() { return Objects.hash(campaignId, skuId); }
}
