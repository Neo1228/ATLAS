package com.atlas.inventory.persistence.repository;

import com.atlas.inventory.persistence.entity.Pool;
import com.atlas.inventory.persistence.entity.PoolId;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PoolRepository extends JpaRepository<Pool, PoolId> {
}
