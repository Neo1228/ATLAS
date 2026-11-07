package com.atlas.catalog.repository;

import com.atlas.catalog.persistence.entity.Seller;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface SellerRepository extends JpaRepository<Seller, UUID> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT s FROM Seller s WHERE s.sellerId = :sellerId")
    Optional<Seller> findByIdForUpdate(@Param("sellerId") UUID sellerId);
}
