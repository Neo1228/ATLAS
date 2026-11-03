package com.atlas.inventory.persistence.entity;

import java.io.Serializable;
import java.util.Objects;
import java.util.UUID;
import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;

@Embeddable
public class InventoryPoolId implements Serializable {
    private static final long serialVersionUID = 1L;

    @Column(name = "seller_id", nullable = false)
    private UUID sellerId;

    @Column(name = "sku_id", nullable = false, length = 128)
    private String skuId;

    protected InventoryPoolId() {
    }

    public InventoryPoolId(UUID sellerId, String skuId) {
        this.sellerId = sellerId;
        this.skuId = skuId;
    }

    public UUID getSellerId() { return sellerId; }
    public String getSkuId() { return skuId; }

    @Override
    public boolean equals(Object other) {
        if (this == other) return true;
        if (!(other instanceof InventoryPoolId that)) return false;
        return Objects.equals(sellerId, that.sellerId) && Objects.equals(skuId, that.skuId);
    }

    @Override
    public int hashCode() { return Objects.hash(sellerId, skuId); }
}
