package com.atlas.order.persistence.entity;

import com.atlas.order.persistence.state.BuyerCheckoutState;
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
@Table(name = "buyer_checkout")
public class BuyerCheckout {
    @Id
    @Column(name = "checkout_id", nullable = false)
    @GeneratedValue
    @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
    private UUID checkoutId;

    @Column(name = "buyer_ref", nullable = false, length = 256)
    private String buyerRef;

    @Column(name = "idempotency_key", nullable = false, length = 256)
    private String idempotencyKey;

    @Enumerated(EnumType.STRING)
    @Column(name = "state", nullable = false, length = 32)
    private BuyerCheckoutState state;

    @Column(name = "total_won", nullable = false)
    private long totalWon;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected BuyerCheckout() {
    }

    public BuyerCheckout(String buyerRef, String idempotencyKey, BuyerCheckoutState state,
                         long totalWon, Instant createdAt, Instant updatedAt) {
        if (buyerRef == null || buyerRef.isBlank() || idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new IllegalArgumentException("Buyer and idempotency key are required");
        }
        if (state == null || totalWon < 0 || createdAt == null || updatedAt == null) {
            throw new IllegalArgumentException("Invalid checkout data");
        }
        this.buyerRef = buyerRef;
        this.idempotencyKey = idempotencyKey;
        this.state = state;
        this.totalWon = totalWon;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    public UUID checkoutId() {
        return checkoutId;
    }

    public String buyerRef() {
        return buyerRef;
    }

    public String idempotencyKey() {
        return idempotencyKey;
    }

    public BuyerCheckoutState state() {
        return state;
    }

    public long totalWon() {
        return totalWon;
    }

    public Instant createdAt() {
        return createdAt;
    }

    public Instant updatedAt() {
        return updatedAt;
    }

    public void update(BuyerCheckoutState state, long totalWon, Instant updatedAt) {
        if (state == null || totalWon < 0 || updatedAt == null) {
            throw new IllegalArgumentException("Invalid checkout update");
        }
        this.state = state;
        this.totalWon = totalWon;
        this.updatedAt = updatedAt;
    }
}
