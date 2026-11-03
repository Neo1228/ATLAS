package com.atlas.campaign.repository;

import com.atlas.campaign.persistence.entity.SellerSku;
import com.atlas.campaign.persistence.entity.SellerSkuId;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SellerSkuRepository extends JpaRepository<SellerSku, SellerSkuId> {

    Optional<SellerSku> findBySellerIdAndSkuId(UUID sellerId, String skuId);
}
