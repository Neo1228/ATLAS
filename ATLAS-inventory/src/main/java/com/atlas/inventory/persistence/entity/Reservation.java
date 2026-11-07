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
import lombok.NoArgsConstructor;
import org.hibernate.annotations.UuidGenerator;

@Entity
@Table(name="inventory_reservation",uniqueConstraints=@UniqueConstraint(name="uk_inventory_reservation_order_sku",columnNames={"order_id","seller_id","sku_id"}))
@Getter
@NoArgsConstructor
public class Reservation {
    @Id @GeneratedValue @UuidGenerator(style=UuidGenerator.Style.VERSION_7) @Column(name="reservation_id") private UUID reservationId;
    @Column(name="order_id",nullable=false) private UUID orderId;
    @Column(name="campaign_id") private UUID campaignId;
    @Column(name="seller_id",nullable=false) private UUID sellerId;
    @Column(name="sku_id",nullable=false,length=128) private String skuId;
    @Column(name="location_id",nullable=false) private UUID locationId;
    @Column(name = "restored_quantity", nullable = false)
    private int restoredQuantity;
    @Column(nullable=false) private int quantity;
    @Enumerated(EnumType.STRING) @Column(nullable=false,length=16) private ReservationStatus status;
    public Reservation(UUID orderId,UUID campaignId,UUID sellerId,String skuId,int quantity,ReservationStatus status,UUID locationId){this.orderId=orderId;this.campaignId=campaignId;this.sellerId=sellerId;this.skuId=skuId;this.quantity=quantity;this.status=status;this.locationId=locationId;}
    public void setStatus(ReservationStatus status){this.status=status;}
    public void restoreClaimedQuantity(int restored) {
        if (status != ReservationStatus.CLAIMED
                || restored < 1
                || restored > quantity - restoredQuantity) {
            throw new IllegalStateException("refunded quantity exceeds claimed reservation");
        }
        restoredQuantity += restored;
    }
}
