package com.atlas.cart.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.util.UUID;
import org.hibernate.annotations.UuidGenerator;

@Entity
@Table(name = "shopping_cart_item", uniqueConstraints = @UniqueConstraint(
        name = "uk_shopping_cart_item_cart_seller_sku", columnNames = {"cart_id", "seller_id", "sku_id"}))
public class CartItem {
    @Id
    @Column(name = "cart_item_id", nullable = false)
    @GeneratedValue
    @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
    private UUID cartItemId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "cart_id", referencedColumnName = "cart_id", insertable = false, updatable = false)
    private Cart cart;

    @Column(name = "cart_id", nullable = false)
    private UUID cartId;

    @Column(name = "seller_id", nullable = false)
    private UUID sellerId;

    @Column(name = "sku_id", nullable = false, length = 128)
    private String skuId;

    @Column(name = "quantity", nullable = false)
    private int quantity;

    protected CartItem() {
    }

    CartItem(Cart cart, UUID sellerId, String skuId, int quantity) {
        this.cart = cart;
        this.cartId = cart.cartId();
        this.sellerId = sellerId;
        this.skuId = skuId;
        this.quantity = quantity;
    }

    public UUID cartItemId() {
        return cartItemId;
    }

    public UUID sellerId() {
        return sellerId;
    }

    public String skuId() {
        return skuId;
    }

    public int quantity() {
        return quantity;
    }

    void setQuantity(int quantity) {
        this.quantity = quantity;
    }
}
