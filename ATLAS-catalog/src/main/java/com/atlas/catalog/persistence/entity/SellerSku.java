package com.atlas.catalog.persistence.entity;

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
@Getter
public class SellerSku {
    @Id
    @Column(nullable = false)
    private UUID sellerId;

    @Id
    @Column(nullable = false, length = 128)
    private String skuId;

    @Column(nullable = false)
    private UUID productId;

    @Column(nullable = false)
    private long priceWon;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected SellerSku() { }

    public SellerSku(UUID sellerId, UUID productId, String skuId, long priceWon, Instant createdAt) {
        this.sellerId = sellerId;
        this.productId = productId;
        this.skuId = skuId;
        this.priceWon = priceWon;
        this.createdAt = createdAt;
    }

    public void updatePrice(long priceWon) {
        this.priceWon = priceWon;
    }
}
