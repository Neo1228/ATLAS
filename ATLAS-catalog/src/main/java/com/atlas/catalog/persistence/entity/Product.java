package com.atlas.catalog.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
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

    @Column(nullable = false, columnDefinition = "TEXT")
    private String name;

    @Column(columnDefinition = "TEXT")
    private String description;

    protected Product() { }

    public Product(UUID sellerId, String name, String description) {
        this.sellerId = sellerId;
        this.name = name;
        this.description = description;
    }
}
