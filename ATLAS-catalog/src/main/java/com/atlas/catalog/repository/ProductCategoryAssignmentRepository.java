package com.atlas.catalog.repository;

import com.atlas.catalog.persistence.entity.ProductCategoryAssignment;
import com.atlas.catalog.persistence.entity.ProductCategoryAssignmentId;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ProductCategoryAssignmentRepository
        extends JpaRepository<ProductCategoryAssignment, ProductCategoryAssignmentId> {
    List<ProductCategoryAssignment> findAllByProductIdInOrderByProductIdAscCategoryIdAsc(List<UUID> productIds);
    List<ProductCategoryAssignment> findAllBySellerIdAndProductIdOrderByCategoryIdAsc(UUID sellerId, UUID productId);
    void deleteAllBySellerIdAndProductId(UUID sellerId, UUID productId);
}
