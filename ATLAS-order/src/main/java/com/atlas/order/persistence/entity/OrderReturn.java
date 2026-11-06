package com.atlas.order.persistence.entity;

import com.atlas.order.persistence.state.OrderReturnStatus;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.hibernate.annotations.UuidGenerator;

@Entity
@Table(name = "order_return")
public class OrderReturn {
    @Id @GeneratedValue @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
    @Column(name = "return_id", nullable = false)
    private UUID returnId;
    @Column(name = "order_id", nullable = false) private UUID orderId;
    @Column(name = "seller_id", nullable = false) private UUID sellerId;
    @Column(name = "buyer_ref", nullable = false, length = 256) private String buyerRef;
    @Column(name = "idempotency_key", nullable = false, length = 128) private String idempotencyKey;
    @Enumerated(EnumType.STRING) @Column(name = "return_status", nullable = false, length = 32)
    private OrderReturnStatus status;
    @Column(name = "requested_at", nullable = false) private Instant requestedAt;
    @Column(name = "received_at") private Instant receivedAt;
    @Column(name = "inspected_at") private Instant inspectedAt;
    @OneToMany(mappedBy = "orderReturn", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<OrderReturnItem> items = new ArrayList<>();

    protected OrderReturn() { }
    public OrderReturn(UUID orderId, UUID sellerId, String buyerRef, String idempotencyKey, Instant requestedAt) {
        this.orderId = orderId; this.sellerId = sellerId; this.buyerRef = buyerRef;
        this.idempotencyKey = idempotencyKey; this.requestedAt = requestedAt;
        this.status = OrderReturnStatus.REQUESTED;
    }
    public void addItem(OrderReturnItem item) { items.add(item); item.setOrderReturn(this); }
    public void receive(Instant at) { receivedAt = at; status = OrderReturnStatus.RECEIVED; }
    public void inspect(OrderReturnStatus result, Instant at) { status = result; inspectedAt = at; }
    public UUID returnId() { return returnId; }
    public UUID orderId() { return orderId; }
    public UUID sellerId() { return sellerId; }
    public String buyerRef() { return buyerRef; }
    public String idempotencyKey() { return idempotencyKey; }
    public OrderReturnStatus status() { return status; }
    public Instant requestedAt() { return requestedAt; }
    public Instant receivedAt() { return receivedAt; }
    public Instant inspectedAt() { return inspectedAt; }
    public List<OrderReturnItem> items() { return List.copyOf(items); }
}
