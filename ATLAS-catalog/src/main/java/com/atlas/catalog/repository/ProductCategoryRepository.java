package com.atlas.catalog.repository;

import com.atlas.catalog.persistence.entity.ProductCategory;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ProductCategoryRepository extends JpaRepository<ProductCategory, UUID> {
    Optional<ProductCategory> findBySellerIdAndCode(UUID sellerId, String code);
    Optional<ProductCategory> findByCategoryIdAndSellerId(UUID categoryId, UUID sellerId);
    List<ProductCategory> findAllBySellerIdOrderByCode(UUID sellerId);
    boolean existsBySellerIdAndCode(UUID sellerId, String code);
}
