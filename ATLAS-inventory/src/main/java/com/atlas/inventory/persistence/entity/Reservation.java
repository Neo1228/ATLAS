package com.atlas.inventory.persistence.entity;

import com.atlas.inventory.persistence.state.ReservationStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.util.UUID;
import lombok.Getter;
import org.hibernate.annotations.UuidGenerator;

@Entity
@Table(name = "inventory_reservation", uniqueConstraints =
        @UniqueConstraint(name = "uk_inventory_reservation_order_sku", columnNames = {"order_id", "seller_id", "sku_id"}))
public class Reservation {
    @Id
    @GeneratedValue
    @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
    @Column(name = "reservation_id", nullable = false)
    @Getter
    private UUID reservationId;

    @Column(name = "order_id", nullable = false)
    @Getter
    private UUID orderId;

    @Column(name = "campaign_id")
    @Getter
    private UUID campaignId;

    @Column(name = "seller_id", nullable = false)
    @Getter
    private UUID sellerId;

    @Column(name = "sku_id", nullable = false, length = 128)
    @Getter
    private String skuId;

    @Column(nullable = false)
    @Getter
    private int quantity;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    @Getter
    private ReservationStatus status;

    @Column(name = "restored_quantity", nullable = false)
    @Getter
    private int restoredQuantity;

    protected Reservation() {
    }

    public Reservation(UUID orderId, UUID campaignId, UUID sellerId, String skuId, int quantity,
                       ReservationStatus status) {
        this.orderId = orderId;
        this.campaignId = campaignId;
        this.sellerId = sellerId;
        this.skuId = skuId;
        this.quantity = quantity;
        this.status = status;
    }

    public void setStatus(ReservationStatus status) {
        this.status = status;
    }

    public void restoreClaimedQuantity(int quantity) {
        if (status != ReservationStatus.CLAIMED || quantity < 1 || quantity > this.quantity - restoredQuantity) {
            throw new IllegalStateException("refunded quantity exceeds claimed reservation");
        }
        restoredQuantity += quantity;
    }
}
