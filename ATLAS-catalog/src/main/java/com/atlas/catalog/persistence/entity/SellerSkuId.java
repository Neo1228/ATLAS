package com.atlas.catalog.persistence.entity;

import java.io.Serializable;
import java.util.Objects;
import java.util.UUID;

public class SellerSkuId implements Serializable {
    private static final long serialVersionUID = 1L;

    private UUID sellerId;
    private String skuId;

    public SellerSkuId() { }

    public SellerSkuId(UUID sellerId, String skuId) {
        this.sellerId = sellerId;
        this.skuId = skuId;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) return true;
        if (!(other instanceof SellerSkuId that)) return false;
        return Objects.equals(sellerId, that.sellerId) && Objects.equals(skuId, that.skuId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(sellerId, skuId);
    }
}
