package com.atlas.catalog.repository;

import com.atlas.catalog.persistence.entity.ProductCategory;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ProductCategoryRepository extends JpaRepository<ProductCategory, UUID> {
    Optional<ProductCategory> findBySellerIdAndCode(UUID sellerId, String code);
    Optional<ProductCategory> findByCategoryIdAndSellerId(UUID categoryId, UUID sellerId);
    List<ProductCategory> findAllBySellerIdOrderByCode(UUID sellerId);
    boolean existsBySellerIdAndCode(UUID sellerId, String code);

    @Query(value = """
            WITH RECURSIVE descendants(category_id) AS (
                SELECT category_id FROM product_category WHERE category_id = :categoryId AND seller_id = :sellerId
                UNION
                SELECT child.category_id FROM product_category child JOIN descendants parent
                    ON child.parent_category_id = parent.category_id WHERE child.seller_id = :sellerId
            ) SELECT EXISTS (SELECT 1 FROM descendants WHERE category_id = :parentId)
            """, nativeQuery = true)
    boolean isDescendant(@Param("sellerId") UUID sellerId, @Param("categoryId") UUID categoryId,
            @Param("parentId") UUID parentId);

}
