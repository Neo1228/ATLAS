package com.atlas.order.persistence.entity;

import com.atlas.order.exception.OrderException;
import com.atlas.order.persistence.state.FulfillmentStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(name = "order_fulfillment")
public class OrderFulfillment {
    @Id
    @Column(name = "order_id", nullable = false)
    private UUID orderId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private FulfillmentStatus status;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "in_progress_at")
    private Instant inProgressAt;

    @Column(name = "shipped_at")
    private Instant shippedAt;

    @Column(name = "delivered_at")
    private Instant deliveredAt;

    protected OrderFulfillment() {}

    public OrderFulfillment(UUID orderId, Instant now) {
        this.orderId = Objects.requireNonNull(orderId, "orderId");
        this.status = FulfillmentStatus.UNFULFILLED;
        this.createdAt = Objects.requireNonNull(now, "now");
        this.updatedAt = now;
    }

    public boolean transitionTo(FulfillmentStatus target, Instant now) {
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(now, "now");
        if (target == status) return false;
        boolean canAdvance = switch (status) {
            case UNFULFILLED -> target == FulfillmentStatus.IN_PROGRESS;
            case IN_PROGRESS -> target == FulfillmentStatus.SHIPPED;
            case SHIPPED -> target == FulfillmentStatus.DELIVERED;
            case DELIVERED -> false;
        };
        if (!canAdvance) {
            throw new OrderException(OrderException.Code.CONFLICT, "Fulfillment transition must advance one state");
        }
        status = target;
        updatedAt = now;
        switch (target) {
            case IN_PROGRESS -> inProgressAt = now;
            case SHIPPED -> shippedAt = now;
            case DELIVERED -> deliveredAt = now;
            case UNFULFILLED -> throw new OrderException(OrderException.Code.CONFLICT, "Invalid fulfillment transition");
        }
        return true;
    }

    public UUID orderId() { return orderId; }
    public FulfillmentStatus status() { return status; }
    public Instant createdAt() { return createdAt; }
    public Instant updatedAt() { return updatedAt; }
    public Instant inProgressAt() { return inProgressAt; }
    public Instant shippedAt() { return shippedAt; }
    public Instant deliveredAt() { return deliveredAt; }
}
