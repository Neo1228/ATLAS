package com.atlas.order.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.io.Serializable;
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(name = "order_shipment_item")
@IdClass(OrderShipmentItem.Key.class)
public class OrderShipmentItem {
    @Id
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "shipment_id", nullable = false)
    private OrderShipment shipment;

    @Id
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "order_item_id", nullable = false)
    private OrderItem orderItem;
    @Column(name = "order_id", nullable = false)
    private UUID orderId;

    @Column(name = "quantity", nullable = false)
    private int quantity;

    protected OrderShipmentItem() { }

    public OrderShipmentItem(OrderShipment shipment, OrderItem orderItem, int quantity) {
        if (shipment == null || orderItem == null || quantity <= 0) {
            throw new IllegalArgumentException("Invalid shipment item");
        }
        this.shipment = shipment;
        this.orderItem = orderItem;
        this.orderId = shipment.orderId();
        this.quantity = quantity;
    }

    public UUID shipmentId() { return shipment.getShipmentId(); }
    public UUID orderItemId() { return orderItem.orderItemId(); }
    public OrderShipment shipment() { return shipment; }
    public OrderItem orderItem() { return orderItem; }
    public int quantity() { return quantity; }

    public static class Key implements Serializable {
        private static final long serialVersionUID = 1L;
        private UUID shipment;
        private UUID orderItem;
        public Key() { }
        public Key(UUID shipment, UUID orderItem) { this.shipment = shipment; this.orderItem = orderItem; }
        @Override public boolean equals(Object other) {
            return other instanceof Key key && Objects.equals(shipment, key.shipment)
                    && Objects.equals(orderItem, key.orderItem);
        }
        @Override public int hashCode() { return Objects.hash(shipment, orderItem); }
    }
}
