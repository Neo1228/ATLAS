package com.atlas.admission.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import java.io.Serializable;
import java.util.Objects;
import java.util.UUID;

@Entity
@IdClass(AdmissionPolicy.AdmissionPolicyId.class)
@Table(name = "seller_sku_admission_policy")
public class AdmissionPolicy {
    @Id
    @Column(name = "seller_id", nullable = false)
    private UUID sellerId;

    @Id
    @Column(name = "sku_id", nullable = false, length = 128)
    private String skuId;

    @Column(name = "max_active_checkouts", nullable = false)
    private int maxActiveCheckouts;

    protected AdmissionPolicy() {
    }

    public AdmissionPolicy(UUID sellerId, String skuId, int maxActiveCheckouts) {
        this.sellerId = Objects.requireNonNull(sellerId, "sellerId");
        this.skuId = Objects.requireNonNull(skuId, "skuId");
        if (maxActiveCheckouts < 1) throw new IllegalArgumentException("maxActiveCheckouts must be positive");
        this.maxActiveCheckouts = maxActiveCheckouts;
    }

    public UUID getSellerId() {
        return sellerId;
    }

    public String getSkuId() {
        return skuId;
    }

    public int getMaxActiveCheckouts() {
        return maxActiveCheckouts;
    }

    public void setMaxActiveCheckouts(int maxActiveCheckouts) {
        if (maxActiveCheckouts < 1) throw new IllegalArgumentException("maxActiveCheckouts must be positive");
        this.maxActiveCheckouts = maxActiveCheckouts;
    }

    public static class AdmissionPolicyId implements Serializable {
        private static final long serialVersionUID = 1L;
        private UUID sellerId;
        private String skuId;

        public AdmissionPolicyId() {
        }

        public AdmissionPolicyId(UUID sellerId, String skuId) {
            this.sellerId = sellerId;
            this.skuId = skuId;
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) return true;
            if (!(other instanceof AdmissionPolicyId id)) return false;
            return Objects.equals(sellerId, id.sellerId) && Objects.equals(skuId, id.skuId);
        }

        @Override
        public int hashCode() {
            return Objects.hash(sellerId, skuId);
        }
    }
}
