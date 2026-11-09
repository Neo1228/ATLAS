package com.atlas.order.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.util.UUID;
import org.hibernate.annotations.UuidGenerator;

@Entity
@Table(name = "order_item")
public class OrderItem {
    @Id
    @Column(name = "order_item_id", nullable = false)
    @GeneratedValue
    @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
    private UUID orderItemId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "order_id", nullable = false)
    private PurchaseOrder order;

    @Column(name = "product_id", nullable = false)
    private UUID productId;

    @Column(name = "seller_id", nullable = false)
    private UUID sellerId;

    @Column(name = "sku_id", nullable = false, length = 128)
    private String skuId;

    @Column(name = "product_name", nullable = false, columnDefinition = "TEXT")
    private String productName;

    @Column(name = "quantity", nullable = false)
    private int quantity;

    @Column(name = "unit_price_won", nullable = false, updatable = false)
    private long unitPriceWon;

    @Column(name = "coupon_discount_won", nullable = false, updatable = false)
    private long couponDiscountWon;

    protected OrderItem() { }

    public OrderItem(PurchaseOrder order, UUID productId, UUID sellerId, String skuId,
                     String productName, int quantity, long unitPriceWon) {
        this(order, productId, sellerId, skuId, productName, quantity, unitPriceWon, 0);
    }

    public OrderItem(PurchaseOrder order, UUID productId, UUID sellerId, String skuId,
                     String productName, int quantity, long unitPriceWon, long couponDiscountWon) {
        if (order == null || productId == null || sellerId == null || skuId == null || skuId.isBlank()
                || productName == null || productName.isBlank() || quantity <= 0 || unitPriceWon <= 0) {
            throw new IllegalArgumentException("Invalid order item snapshot");
        }
        long grossAmountWon = Math.multiplyExact(unitPriceWon, quantity);
        if (couponDiscountWon < 0 || couponDiscountWon >= grossAmountWon) {
            throw new IllegalArgumentException("Invalid order item coupon allocation");
        }
        long maximumUnitDiscountWon = couponDiscountWon / quantity
                + (couponDiscountWon % quantity == 0 ? 0 : 1);
        if (maximumUnitDiscountWon >= unitPriceWon) {
            throw new IllegalArgumentException("Coupon allocation makes a line unit non-refundable");
        }
        this.order = order;
        this.productId = productId;
        this.sellerId = sellerId;
        this.skuId = skuId;
        this.productName = productName;
        this.quantity = quantity;
        this.unitPriceWon = unitPriceWon;
        this.couponDiscountWon = couponDiscountWon;
    }

    public UUID orderItemId() { return orderItemId; }
    public PurchaseOrder order() { return order; }
    public UUID productId() { return productId; }
    public UUID sellerId() { return sellerId; }
    public String skuId() { return skuId; }
    public String productName() { return productName; }
    public int quantity() { return quantity; }
    public long unitPriceWon() { return unitPriceWon; }
    public long couponDiscountWon() { return couponDiscountWon; }
    public long lineTotalWon() {
        return Math.subtractExact(Math.multiplyExact(unitPriceWon, quantity), couponDiscountWon);
    }

    public long refundAmountWon(int refundQuantity, int previouslyRefundedQuantity) {
        int endQuantity = Math.addExact(previouslyRefundedQuantity, refundQuantity);
        if (refundQuantity <= 0 || previouslyRefundedQuantity < 0 || endQuantity > quantity) {
            throw new IllegalArgumentException("Refund quantity exceeds order item snapshot");
        }
        long discountPerUnit = couponDiscountWon / quantity;
        int discountRemainder = (int) (couponDiscountWon % quantity);
        int previouslyDiscountedUnits = Math.min(previouslyRefundedQuantity, discountRemainder);
        int newlyDiscountedUnits = Math.min(endQuantity, discountRemainder) - previouslyDiscountedUnits;
        long refundDiscountWon = Math.addExact(Math.multiplyExact(discountPerUnit, refundQuantity),
                newlyDiscountedUnits);
        return Math.subtractExact(Math.multiplyExact(unitPriceWon, refundQuantity), refundDiscountWon);
    }
}
