package com.atlas.order.persistence.entity;

import com.atlas.order.persistence.state.OrderSource;
import com.atlas.order.persistence.state.OrderState;
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
@Table(name = "purchase_order")
public class PurchaseOrder {
    @Column(name = "checkout_id")
    private UUID checkoutId;
    @Id
    @Column(name = "order_id", nullable = false)
    @GeneratedValue
    @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
    private UUID orderId;

    @Enumerated(EnumType.STRING)
    @Column(name = "order_source", nullable = false, length = 16)
    private OrderSource source;

    @Column(name = "campaign_id")
    private UUID campaignId;

    @Column(name = "ticket_id")
    private UUID ticketId;

    @Column(name = "seller_id", nullable = false)
    private UUID sellerId;

    @Column(name = "buyer_ref", nullable = false, length = 256)
    private String buyerRef;

    @Column(name = "checkout_key", length = 256)
    private String checkoutKey;

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

    public PurchaseOrder(UUID campaignId, UUID sellerId, UUID ticketId, String buyerRef,
                         OrderState state, Instant checkoutExpiresAt, UUID paymentAttemptId,
                         Instant createdAt, Instant updatedAt) {
        this(OrderSource.CAMPAIGN, campaignId, ticketId, sellerId, buyerRef, null, state,
                checkoutExpiresAt, paymentAttemptId, createdAt, updatedAt);
    }

    public PurchaseOrder(UUID sellerId, String buyerRef, String checkoutKey, OrderState state,
                         Instant checkoutExpiresAt, Instant createdAt, Instant updatedAt) {
        this(OrderSource.CART, null, null, sellerId, buyerRef, checkoutKey, state,
                checkoutExpiresAt, null, createdAt, updatedAt);
    }

    private PurchaseOrder(OrderSource source, UUID campaignId, UUID ticketId, UUID sellerId,
                          String buyerRef, String checkoutKey, OrderState state,
                          Instant checkoutExpiresAt, UUID paymentAttemptId, Instant createdAt, Instant updatedAt) {
        if (source == null || sellerId == null || buyerRef == null || buyerRef.isBlank()
                || state == null || checkoutExpiresAt == null || createdAt == null || updatedAt == null) {
            throw new IllegalArgumentException("Invalid order");
        }
        if (source == OrderSource.CAMPAIGN && (campaignId == null || ticketId == null || checkoutKey != null)) {
            throw new IllegalArgumentException("Campaign orders require campaign and ticket metadata only");
        }
        if (source == OrderSource.CART && (campaignId != null || ticketId != null || checkoutKey == null
                || checkoutKey.isBlank())) {
            throw new IllegalArgumentException("Cart orders require a checkout key and no campaign metadata");
        }
        this.source = source;
        this.campaignId = campaignId;
        this.ticketId = ticketId;
        this.sellerId = sellerId;
        this.buyerRef = buyerRef;
        this.checkoutKey = checkoutKey;
        this.state = state;
        this.checkoutExpiresAt = checkoutExpiresAt;
        this.paymentAttemptId = paymentAttemptId;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    public UUID orderId() { return orderId; }
    public OrderSource source() { return source; }
    public UUID campaignId() { return campaignId; }
    public UUID sellerId() { return sellerId; }
    public UUID ticketId() { return ticketId; }
    public String buyerRef() { return buyerRef; }
    public String checkoutKey() { return checkoutKey; }
    public OrderState state() { return state; }
    public Instant checkoutExpiresAt() { return checkoutExpiresAt; }
    public UUID paymentAttemptId() { return paymentAttemptId; }
    public Instant createdAt() { return createdAt; }
    public Instant updatedAt() { return updatedAt; }

    public void updateState(OrderState state, UUID paymentAttemptId, Instant updatedAt) {
        this.state = state;
        if (paymentAttemptId != null) this.paymentAttemptId = paymentAttemptId;
        this.updatedAt = updatedAt;
    }
    public UUID checkoutId() {
        return checkoutId;
    }

    public void setCheckoutId(UUID checkoutId) {
        this.checkoutId = checkoutId;
    }
}
