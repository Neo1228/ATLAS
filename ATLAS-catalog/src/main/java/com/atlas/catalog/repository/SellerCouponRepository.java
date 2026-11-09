package com.atlas.catalog.repository;

import com.atlas.catalog.persistence.entity.SellerCoupon;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SellerCouponRepository extends JpaRepository<SellerCoupon, UUID> {
    Optional<SellerCoupon> findBySellerIdAndCode(UUID sellerId, String code);
    boolean existsBySellerIdAndCode(UUID sellerId, String code);
}
