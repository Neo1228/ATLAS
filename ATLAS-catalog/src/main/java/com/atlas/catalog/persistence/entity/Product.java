package com.atlas.catalog.persistence.entity;

import com.atlas.catalog.persistence.state.ProductSalesChannel;
import com.atlas.catalog.persistence.state.ProductStatus;
import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.MapKeyColumn;
import jakarta.persistence.MapKeyEnumerated;
import jakarta.persistence.Table;
import java.util.EnumMap;
import java.util.Map;
import java.util.UUID;
import lombok.Getter;
import org.hibernate.annotations.UuidGenerator;

@Entity
@Table(name = "product")
@Getter
public class Product {
    @Id
    @GeneratedValue
    @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
    private UUID productId;

    @Column(nullable = false)
    private UUID sellerId;

    @Column(nullable = false)
    private UUID categoryId;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String name;

    @Column(columnDefinition = "TEXT")
    private String description;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private ProductStatus status = ProductStatus.ACTIVE;

    @ElementCollection
    @CollectionTable(name = "product_channel_availability", joinColumns = @JoinColumn(name = "product_id"))
    @MapKeyColumn(name = "sales_channel", nullable = false, length = 32)
    @MapKeyEnumerated(EnumType.STRING)
    @Column(name = "available", nullable = false)
    private Map<ProductSalesChannel, Boolean> channelAvailability = defaultChannelAvailability();

    protected Product() { }

    public Product(UUID sellerId, UUID categoryId, String name, String description) {
        this.sellerId = sellerId;
        this.categoryId = categoryId;
        this.name = name;
        this.description = description;
    }

    public void assignCategory(UUID categoryId) {
        this.categoryId = categoryId;
    }

    public void updateStatus(ProductStatus status) {
        this.status = status;
    }

    public void updateChannelAvailability(ProductSalesChannel channel, boolean available) {
        channelAvailability.put(channel, available);
    }

    public boolean isOrderableOn(ProductSalesChannel channel) {
        return status == ProductStatus.ACTIVE && channel != null && Boolean.TRUE.equals(channelAvailability.get(channel));
    }

    private static Map<ProductSalesChannel, Boolean> defaultChannelAvailability() {
        Map<ProductSalesChannel, Boolean> availability = new EnumMap<>(ProductSalesChannel.class);
        for (ProductSalesChannel channel : ProductSalesChannel.values()) availability.put(channel, true);
        return availability;
    }
}
