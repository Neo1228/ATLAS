package com.atlas.inventory.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import java.io.Serializable;
import java.util.Objects;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Embeddable
@Getter
@NoArgsConstructor
public class InventoryPoolId implements Serializable {
    private static final long serialVersionUID = 1L;
    @Column(name = "seller_id", nullable = false) private UUID sellerId;
    @Column(name = "location_id", nullable = false) private UUID locationId;
    @Column(name = "sku_id", nullable = false, length = 128) private String skuId;
    public InventoryPoolId(UUID sellerId, UUID locationId, String skuId) { this.sellerId=sellerId; this.locationId=locationId; this.skuId=skuId; }
    @Override public boolean equals(Object other) { return this==other || other instanceof InventoryPoolId that && Objects.equals(sellerId,that.sellerId) && Objects.equals(locationId,that.locationId) && Objects.equals(skuId,that.skuId); }
    @Override public int hashCode() { return Objects.hash(sellerId,locationId,skuId); }
}
