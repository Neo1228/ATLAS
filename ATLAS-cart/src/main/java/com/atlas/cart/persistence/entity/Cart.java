package com.atlas.cart.persistence.entity;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.hibernate.annotations.UuidGenerator;

@Entity
@Table(name = "shopping_cart", uniqueConstraints = @UniqueConstraint(
        name = "uk_shopping_cart_buyer", columnNames = "buyer_ref"))
public class Cart {
    @Id
    @Column(name = "cart_id", nullable = false)
    @GeneratedValue
    @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
    private UUID cartId;

    @Column(name = "buyer_ref", nullable = false, length = 256)
    private String buyerRef;

    @OneToMany(mappedBy = "cart", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    private List<CartItem> items = new ArrayList<>();

    protected Cart() {
    }

    public Cart(String buyerRef) {
        this.buyerRef = buyerRef;
    }

    public UUID cartId() {
        return cartId;
    }

    public String buyerRef() {
        return buyerRef;
    }

    public List<CartItem> items() {
        return items;
    }

    public void setItemQuantity(UUID sellerId, String skuId, int quantity) {
        CartItem matching = items.stream()
                .filter(item -> item.sellerId().equals(sellerId) && item.skuId().equals(skuId))
                .findFirst()
                .orElse(null);
        if (matching == null) {
            items.add(new CartItem(this, sellerId, skuId, quantity));
        } else {
            matching.setQuantity(quantity);
        }
    }

    public void removeItem(UUID sellerId, String skuId) {
        items.removeIf(item -> item.sellerId().equals(sellerId) && item.skuId().equals(skuId));
    }

    public void clearItems() {
        items.clear();
    }
}
