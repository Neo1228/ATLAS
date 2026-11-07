package com.atlas.order.persistence.entity;

import com.atlas.order.persistence.state.OrderShipmentStatus;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.hibernate.annotations.UuidGenerator;

@Entity
@Table(name = "order_shipment")
public class OrderShipment {
    @Id
    @GeneratedValue
    @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
    @Column(name = "shipment_id", nullable = false)
    private UUID shipmentId;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "order_id", nullable = false)
    private PurchaseOrder order;
    @Column(name = "idempotency_key", length = 128)
    private String idempotencyKey;
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private OrderShipmentStatus status;
    @Column(name = "carrier_code", length = 64)
    private String carrierCode;
    @Column(name = "tracking_number", length = 128)
    private String trackingNumber;
    @Column(name = "shipped_at", nullable = false)
    private Instant shippedAt;
    @Column(name = "delivered_at")
    private Instant deliveredAt;
    @OneToMany(mappedBy = "shipment", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("orderItem ASC")
    private List<OrderShipmentItem> items = new ArrayList<>();

    protected OrderShipment() { }
    public OrderShipment(PurchaseOrder order, String idempotencyKey, String carrierCode,
                         String trackingNumber, Instant shippedAt) {
        this.order = Objects.requireNonNull(order, "order");
        this.idempotencyKey = Objects.requireNonNull(idempotencyKey, "idempotencyKey");
        this.carrierCode = carrierCode;
        this.trackingNumber = trackingNumber;
        this.status = OrderShipmentStatus.SHIPPED;
        this.shippedAt = Objects.requireNonNull(shippedAt, "shippedAt");
    }
    public void addItem(OrderItem item, int quantity) { items.add(new OrderShipmentItem(this, item, quantity)); }
    public void deliver(Instant now) {
        if (status == OrderShipmentStatus.DELIVERED) return;
        status = OrderShipmentStatus.DELIVERED;
        deliveredAt = Objects.requireNonNull(now, "now");
    }
    public UUID shipmentId() { return shipmentId; }
    public UUID getShipmentId() { return shipmentId; }
    public UUID orderId() { return order.orderId(); }
    public PurchaseOrder order() { return order; }
    public String idempotencyKey() { return idempotencyKey; }
    public OrderShipmentStatus status() { return status; }
    public String carrierCode() { return carrierCode; }
    public String trackingNumber() { return trackingNumber; }
    public Instant shippedAt() { return shippedAt; }
    public Instant deliveredAt() { return deliveredAt; }
    public List<OrderShipmentItem> items() { return List.copyOf(items); }
}
