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
    @Column(name = "status", nullable = false, length = 24)
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

    protected OrderFulfillment() { }
    public OrderFulfillment(UUID orderId, Instant now) {
        this.orderId = Objects.requireNonNull(orderId);
        this.status = FulfillmentStatus.UNFULFILLED;
        this.createdAt = Objects.requireNonNull(now);
        this.updatedAt = now;
    }

    public boolean start(Instant now) {
        Objects.requireNonNull(now);
        if (status == FulfillmentStatus.IN_PROGRESS) return false;
        if (status != FulfillmentStatus.UNFULFILLED) {
            throw new OrderException(OrderException.Code.CONFLICT, "Fulfillment has already advanced");
        }
        status = FulfillmentStatus.IN_PROGRESS;
        inProgressAt = now;
        updatedAt = now;
        return true;
    }

    /** 출고 수량을 바탕으로 요약 상태를 계산한다. 호출자가 상태를 직접 지정할 수 없다. */
    public boolean refreshSummary(long totalShippable, long totalDispatched, long totalDelivered, Instant now) {
        Objects.requireNonNull(now);
        if (totalShippable <= 0) return false;
        if (totalDispatched < 0 || totalDelivered < 0 || totalDelivered > totalDispatched
                || totalDispatched > totalShippable) {
            throw new IllegalStateException("Invalid shipment totals");
        }
        FulfillmentStatus target = totalDelivered == totalShippable
                ? FulfillmentStatus.DELIVERED
                : totalDispatched == totalShippable ? FulfillmentStatus.SHIPPED
                : totalDispatched > 0 ? FulfillmentStatus.PARTIALLY_SHIPPED
                : FulfillmentStatus.IN_PROGRESS;
        boolean changed = target != status;
        status = target;
        if (inProgressAt == null) inProgressAt = now;
        if (target == FulfillmentStatus.SHIPPED || target == FulfillmentStatus.DELIVERED) {
            if (shippedAt == null) shippedAt = now;
        }
        if (target == FulfillmentStatus.DELIVERED && deliveredAt == null) deliveredAt = now;
        updatedAt = now;
        return changed;
    }

    public UUID orderId() { return orderId; }
    public FulfillmentStatus status() { return status; }
    public Instant createdAt() { return createdAt; }
    public Instant updatedAt() { return updatedAt; }
    public Instant inProgressAt() { return inProgressAt; }
    public Instant shippedAt() { return shippedAt; }
    public Instant deliveredAt() { return deliveredAt; }
}
