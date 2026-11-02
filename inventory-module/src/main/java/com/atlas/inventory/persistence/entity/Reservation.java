package com.atlas.inventory.persistence.entity;

import com.atlas.inventory.persistence.state.ReservationStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.UUID;

@Entity
@Table(name = "inventory_reservation")
public class Reservation {

    @Id
    @Column(name = "order_id", nullable = false)
    private UUID orderId;

    @Column(name = "campaign_id", nullable = false)
    private UUID campaignId;

    @Column(name = "sku_id", nullable = false, length = 128)
    private String skuId;

    @Column(nullable = false)
    private int quantity;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private ReservationStatus status;

    protected Reservation() {
    }

    public Reservation(UUID orderId, UUID campaignId, String skuId, int quantity,
                                      ReservationStatus status) {
        this.orderId = orderId;
        this.campaignId = campaignId;
        this.skuId = skuId;
        this.quantity = quantity;
        this.status = status;
    }

    public UUID getOrderId() { return orderId; }
    public UUID getCampaignId() { return campaignId; }
    public String getSkuId() { return skuId; }
    public int getQuantity() { return quantity; }
    public ReservationStatus getStatus() { return status; }

    public void setStatus(ReservationStatus status) { this.status = status; }
}
