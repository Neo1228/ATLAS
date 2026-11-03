package com.atlas.campaign.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.Getter;

@Entity
@IdClass(SellerSkuId.class)
@Table(name = "seller_sku")
public class SellerSku {

    @Id
    @Column(name = "seller_id", nullable = false)
    @Getter
    private UUID sellerId;

    @Id
    @Column(name = "sku_id", nullable = false, length = 128)
    @Getter
    private String skuId;

    @Column(name = "max_active_checkouts", nullable = false)
    @Getter
    private int maxActiveCheckouts;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected SellerSku() {
    }

    public SellerSku(UUID sellerId, String skuId, int maxActiveCheckouts, Instant createdAt) {
        this.sellerId = sellerId;
        this.skuId = skuId;
        this.maxActiveCheckouts = maxActiveCheckouts;
        this.createdAt = createdAt;
    }

}
