package com.atlas.catalog.repository;

import com.atlas.catalog.persistence.entity.SellerSku;
import com.atlas.catalog.persistence.entity.SellerSkuId;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SellerSkuRepository extends JpaRepository<SellerSku, SellerSkuId> {
    Optional<SellerSku> findBySellerIdAndSkuId(UUID sellerId, String skuId);
    List<SellerSku> findAllBySellerId(UUID sellerId);
    List<SellerSku> findAllBySellerIdAndSkuIdIn(UUID sellerId, List<String> skuIds);
    List<SellerSku> findAllByProductId(UUID productId);
    List<SellerSku> findAllByProductIdIn(List<UUID> productIds);
}
