package com.atlas.order.persistence.entity;

import com.atlas.order.persistence.state.OrderState;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "purchase_order")
public class PurchaseOrder {
    @Id
    @Column(name = "order_id", nullable = false)
    private UUID orderId;
 @Column(name = "seller_id", nullable = false)
 private UUID sellerId;
 @Column(name = "campaign_id", nullable = false)
 private UUID campaignId;
    @Column(name = "ticket_id", nullable = false)
    private UUID ticketId;
    @Column(name = "buyer_ref", nullable = false, length = 256)
    private String buyerRef;
    @Column(name = "sku_id", nullable = false, length = 128)
    private String skuId;
    @Enumerated(EnumType.STRING)
    @Column(name = "state", nullable = false, length = 32)
    private OrderState state;
    @Column(name = "checkout_expires_at", nullable = false)
    private Instant checkoutExpiresAt;
    @Column(name = "payment_attempt_id")
    private UUID paymentAttemptId;
    @Column(name = "created_at", nullable = false)
    private Instant createdAt;
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected PurchaseOrder() { }

 public PurchaseOrder(UUID orderId, UUID campaignId, UUID sellerId, UUID ticketId, String buyerRef, String skuId,
 OrderState state, Instant checkoutExpiresAt, UUID paymentAttemptId, Instant createdAt, Instant updatedAt) {
 this.orderId = orderId;
 this.campaignId = campaignId;
 this.sellerId = sellerId;
 this.ticketId = ticketId;
 this.buyerRef = buyerRef;
 this.skuId = skuId;
 this.state = state;
 this.checkoutExpiresAt = checkoutExpiresAt;
 this.paymentAttemptId = paymentAttemptId;
 this.createdAt = createdAt;
 this.updatedAt = updatedAt;
 }

    public UUID orderId() { return orderId; }
    public UUID campaignId() { return campaignId; }
 public UUID sellerId() { return sellerId; }
    public UUID ticketId() { return ticketId; }
    public String buyerRef() { return buyerRef; }
    public String skuId() { return skuId; }
    public OrderState state() { return state; }
    public Instant checkoutExpiresAt() { return checkoutExpiresAt; }
    public UUID paymentAttemptId() { return paymentAttemptId; }
    public void updateState(OrderState state, UUID paymentAttemptId, Instant updatedAt) {
        this.state = state;
        if (paymentAttemptId != null) this.paymentAttemptId = paymentAttemptId;
        this.updatedAt = updatedAt;
    }
}
