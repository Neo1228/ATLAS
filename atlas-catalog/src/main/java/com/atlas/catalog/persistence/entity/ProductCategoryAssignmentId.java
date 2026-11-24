package com.atlas.catalog.persistence.entity;

import java.io.Serializable;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode
public class ProductCategoryAssignmentId implements Serializable {
    private static final long serialVersionUID = 1L;

    private UUID sellerId;
    private UUID productId;
    private UUID categoryId;
}
