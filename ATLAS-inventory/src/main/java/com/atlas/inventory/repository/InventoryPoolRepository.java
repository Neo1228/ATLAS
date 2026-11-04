package com.atlas.inventory.repository;

import com.atlas.inventory.persistence.entity.InventoryPool;
import com.atlas.inventory.persistence.entity.InventoryPoolId;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface InventoryPoolRepository extends JpaRepository<InventoryPool, InventoryPoolId> {
    @Query("select pool from InventoryPool pool where pool.id.sellerId = :sellerId "
            + "and pool.id.skuId in :skuIds order by pool.id.skuId")
    List<InventoryPool> findAllBySellerIdAndSkuIds(@Param("sellerId") UUID sellerId,
            @Param("skuIds") List<String> skuIds);
}
