package com.atlas.catalog.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import java.util.UUID;
import lombok.Getter;

@Entity
@Table(name = "product_category_assignment")
@IdClass(ProductCategoryAssignmentId.class)
@Getter
public class ProductCategoryAssignment {
    @Id
    @Column(name = "seller_id", nullable = false)
    private UUID sellerId;

    @Id
    @Column(name = "product_id", nullable = false)
    private UUID productId;

    @Id
    @Column(name = "category_id", nullable = false)
    private UUID categoryId;

    protected ProductCategoryAssignment() { }

    public ProductCategoryAssignment(UUID sellerId, UUID productId, UUID categoryId) {
        this.sellerId = sellerId;
        this.productId = productId;
        this.categoryId = categoryId;
    }
}