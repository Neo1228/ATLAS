package com.atlas.inventory.repository;

import com.atlas.inventory.persistence.entity.InventoryPool;
import com.atlas.inventory.persistence.entity.InventoryPoolId;
import org.springframework.data.jpa.repository.JpaRepository;

public interface InventoryPoolRepository extends JpaRepository<InventoryPool, InventoryPoolId> {
}
