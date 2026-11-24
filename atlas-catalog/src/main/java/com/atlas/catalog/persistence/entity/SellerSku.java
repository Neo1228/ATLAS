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
    @Column(name = "sale_price_won")
    private Long salePriceWon;

    @Column(name = "sale_starts_at")
    private Instant saleStartsAt;

    @Column(name = "sale_ends_at")
    private Instant saleEndsAt;

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
        if (priceWon <= 0 || salePriceWon != null && salePriceWon >= priceWon) {
            throw new IllegalArgumentException("Regular price must remain above the sale price");
        }
        this.priceWon = priceWon;
    }

    public void setSalePrice(long salePriceWon, Instant saleStartsAt, Instant saleEndsAt) {
        if (salePriceWon <= 0 || salePriceWon >= priceWon || saleStartsAt == null || saleEndsAt == null
                || !saleStartsAt.isBefore(saleEndsAt)) {
            throw new IllegalArgumentException("Invalid SKU sale price or window");
        }
        this.salePriceWon = salePriceWon;
        this.saleStartsAt = saleStartsAt;
        this.saleEndsAt = saleEndsAt;
    }

    public void clearSalePrice() {
        salePriceWon = null;
        saleStartsAt = null;
        saleEndsAt = null;
    }

    public long effectivePriceAt(Instant instant) {
        if (instant == null) throw new IllegalArgumentException("pricing instant is required");
        return salePriceWon != null && !instant.isBefore(saleStartsAt) && instant.isBefore(saleEndsAt)
                ? salePriceWon : priceWon;
    }
}
