package com.atlas.catalog.persistence.entity;

import com.atlas.catalog.persistence.state.SellerCouponRedemptionState;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.UuidGenerator;

@Entity
@Table(name = "seller_coupon_redemption")
public class SellerCouponRedemption {

    @Id
    @Column(name = "redemption_id", nullable = false)
    @GeneratedValue
    @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
    private UUID redemptionId;

    @Column(name = "seller_id", nullable = false)
    private UUID sellerId;

    @Column(name = "coupon_id", nullable = false)
    private UUID couponId;

    @Column(name = "order_id", nullable = false)
    private UUID orderId;

    @Enumerated(EnumType.STRING)
    @Column(name = "state", nullable = false, length = 16)
    private SellerCouponRedemptionState state;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected SellerCouponRedemption() { }

    public SellerCouponRedemption(UUID sellerId, UUID couponId, UUID orderId, Instant createdAt) {
        if (sellerId == null || couponId == null || orderId == null || createdAt == null) {
            throw new IllegalArgumentException("Invalid coupon redemption facts");
        }
        this.sellerId = sellerId;
        this.couponId = couponId;
        this.orderId = orderId;
        this.state = SellerCouponRedemptionState.RESERVED;
        this.createdAt = createdAt;
        this.updatedAt = createdAt;
    }

    public void redeem(Instant at) {
        transition(SellerCouponRedemptionState.REDEEMED, at);
    }

    public void release(Instant at) {
        transition(SellerCouponRedemptionState.RELEASED, at);
    }

    private void transition(SellerCouponRedemptionState next, Instant at) {
        if (state != SellerCouponRedemptionState.RESERVED || at == null) {
            throw new IllegalStateException("Only a reserved coupon redemption can transition");
        }
        state = next;
        updatedAt = at;
    }

    public UUID sellerId() { return sellerId; }
    public UUID couponId() { return couponId; }
    public UUID orderId() { return orderId; }
    public SellerCouponRedemptionState state() { return state; }
}
