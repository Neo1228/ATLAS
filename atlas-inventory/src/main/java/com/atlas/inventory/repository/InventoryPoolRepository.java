package com.atlas.inventory.repository;

import com.atlas.inventory.persistence.entity.InventoryPool;
import com.atlas.inventory.persistence.entity.InventoryPoolId;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;

public interface InventoryPoolRepository extends JpaRepository<InventoryPool, InventoryPoolId> {
    List<InventoryPool> findAllByIdSellerIdAndIdLocationIdOrderByIdSkuId(UUID sellerId, UUID locationId);

    /** SKU 기준 keyset 페이지. {@code afterSkuId}보다 큰 SKU만 정렬 순서대로 {@code limit}개 조회한다. */
    List<InventoryPool> findByIdSellerIdAndIdLocationIdAndIdSkuIdGreaterThanOrderByIdSkuId(
            UUID sellerId, UUID locationId, String afterSkuId, Limit limit);
}
