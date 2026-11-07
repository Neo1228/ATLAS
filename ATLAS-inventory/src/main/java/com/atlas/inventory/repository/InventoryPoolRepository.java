package com.atlas.inventory.repository;

import com.atlas.inventory.persistence.entity.InventoryPool;
import com.atlas.inventory.persistence.entity.InventoryPoolId;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface InventoryPoolRepository extends JpaRepository<InventoryPool, InventoryPoolId> {
    List<InventoryPool> findAllByIdSellerIdAndIdLocationIdOrderByIdSkuId(UUID sellerId, UUID locationId);
}
