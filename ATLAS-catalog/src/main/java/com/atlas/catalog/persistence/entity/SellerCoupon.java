package com.atlas.catalog.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.Getter;
import org.hibernate.annotations.UuidGenerator;

@Entity
@Table(name = "seller_coupon")
@Getter
public class SellerCoupon {
    @Id
    @Column(name = "coupon_id", nullable = false)
    @GeneratedValue
    @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
    private UUID couponId;

    @Column(name = "seller_id", nullable = false)
    private UUID sellerId;

    @Column(nullable = false, length = 32)
    private String code;

    @Column(name = "discount_amount_won", nullable = false)
    private long discountAmountWon;
    @Column(name = "max_redemptions", updatable = false)
    private Long maxRedemptions;

    @Column(name = "reserved_count", nullable = false, updatable = false)
    private long reservedCount;

    @Column(name = "redeemed_count", nullable = false, updatable = false)
    private long redeemedCount;

    @Column(name = "starts_at", nullable = false)
    private Instant startsAt;

    @Column(name = "ends_at", nullable = false)
    private Instant endsAt;

    @Column(nullable = false)
    private boolean active;

    protected SellerCoupon() { }

    public SellerCoupon(UUID sellerId, String code, long discountAmountWon,
                        Instant startsAt, Instant endsAt) {
        this(sellerId, code, discountAmountWon, startsAt, endsAt, null);
    }

    public SellerCoupon(UUID sellerId, String code, long discountAmountWon,
                        Instant startsAt, Instant endsAt, Long maxRedemptions) {
        if (maxRedemptions != null && maxRedemptions <= 0) {
            throw new IllegalArgumentException("Coupon redemption limit must be positive");
        }
        this.sellerId = sellerId;
        this.code = code;
        this.discountAmountWon = discountAmountWon;
        this.startsAt = startsAt;
        this.endsAt = endsAt;
        this.maxRedemptions = maxRedemptions;
        this.active = true;
    }

    public void deactivate() {
        active = false;
    }

    public boolean isUsableAt(Instant at) {
        return active && !at.isBefore(startsAt) && at.isBefore(endsAt);
    }
}
