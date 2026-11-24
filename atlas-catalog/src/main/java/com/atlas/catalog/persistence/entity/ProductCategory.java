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
@Table(name = "product_category")
@Getter
public class ProductCategory {
    public static final String UNCATEGORIZED_CODE = "UNCATEGORIZED";
    public static final String UNCATEGORIZED_NAME = "미분류";

    @Id
    @GeneratedValue
    @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
    private UUID categoryId;

    @Column(nullable = false)
    private UUID sellerId;

    @Column(nullable = false, length = 128)
    private String code;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String name;

    @Column(name = "parent_category_id")
    private UUID parentCategoryId;

    protected ProductCategory() { }

    public ProductCategory(UUID sellerId, String code, String name) {
        this.sellerId = sellerId;
        this.code = code;
        this.name = name;
    }

    public void updateParent(UUID parentCategoryId) {
        if (UNCATEGORIZED_CODE.equals(code) && parentCategoryId != null) {
            throw new IllegalArgumentException("UNCATEGORIZED must remain a root");
        }
        this.parentCategoryId = parentCategoryId;
    }

    public void updateName(String name) {
        if (UNCATEGORIZED_CODE.equals(code) && !UNCATEGORIZED_NAME.equals(name)) {
            throw new IllegalArgumentException("UNCATEGORIZED name is reserved");
        }
        this.name = name;
    }
}
