package com.atlas.catalog.repository;

import com.atlas.catalog.persistence.entity.SellerCouponRedemption;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface SellerCouponRedemptionRepository extends JpaRepository<SellerCouponRedemption, UUID> {
    Optional<SellerCouponRedemption> findBySellerIdAndCouponIdAndOrderId(
            UUID sellerId, UUID couponId, UUID orderId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT r FROM SellerCouponRedemption r WHERE r.sellerId = :sellerId "
            + "AND r.couponId = :couponId AND r.orderId = :orderId")
    Optional<SellerCouponRedemption> findWithLockBySellerIdAndCouponIdAndOrderId(
            @Param("sellerId") UUID sellerId, @Param("couponId") UUID couponId, @Param("orderId") UUID orderId);
}
