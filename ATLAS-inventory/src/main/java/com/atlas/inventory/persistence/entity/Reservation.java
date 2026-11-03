package com.atlas.inventory.persistence.entity;

import com.atlas.inventory.persistence.state.ReservationStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.UUID;
import lombok.Getter;

@Entity
@Table(name = "inventory_reservation")
public class Reservation {

    @Id
    @Column(name = "order_id", nullable = false)
    @Getter
    private UUID orderId;

    @Column(name = "campaign_id", nullable = false)
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

    public void setStatus(ReservationStatus status) { this.status = status; }
}
