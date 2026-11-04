package com.atlas.catalog.repository;

import com.atlas.catalog.persistence.entity.Product;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ProductRepository extends JpaRepository<Product, UUID> {
    boolean existsByProductIdAndSellerId(UUID productId, UUID sellerId);
    List<Product> findAllBySellerId(UUID sellerId);
    List<Product> findAllByProductIdIn(List<UUID> productIds);
}
