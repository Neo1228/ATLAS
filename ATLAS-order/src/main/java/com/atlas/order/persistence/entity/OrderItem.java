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

    @Column(name = "unit_price_won", nullable = false)
    private long unitPriceWon;

    protected OrderItem() { }

    public OrderItem(PurchaseOrder order, UUID productId, UUID sellerId, String skuId,
                     String productName, int quantity, long unitPriceWon) {
        if (order == null || productId == null || sellerId == null || skuId == null || skuId.isBlank()
                || productName == null || productName.isBlank() || quantity <= 0 || unitPriceWon <= 0) {
            throw new IllegalArgumentException("Invalid order item snapshot");
        }
        Math.multiplyExact(unitPriceWon, quantity);
        this.order = order;
        this.productId = productId;
        this.sellerId = sellerId;
        this.skuId = skuId;
        this.productName = productName;
        this.quantity = quantity;
        this.unitPriceWon = unitPriceWon;
    }

    public UUID orderItemId() { return orderItemId; }
    public PurchaseOrder order() { return order; }
    public UUID productId() { return productId; }
    public UUID sellerId() { return sellerId; }
    public String skuId() { return skuId; }
    public String productName() { return productName; }
    public int quantity() { return quantity; }
    public long unitPriceWon() { return unitPriceWon; }
    public long lineTotalWon() { return Math.multiplyExact(unitPriceWon, quantity); }
}
