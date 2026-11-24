package com.atlas.catalog.repository;

import com.atlas.catalog.persistence.entity.SellerCoupon;
import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface SellerCouponRepository extends JpaRepository<SellerCoupon, UUID> {
    Optional<SellerCoupon> findBySellerIdAndCode(UUID sellerId, String code);
    Optional<SellerCoupon> findBySellerIdAndCouponId(UUID sellerId, UUID couponId);
    boolean existsBySellerIdAndCode(UUID sellerId, String code);
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT c FROM SellerCoupon c WHERE c.sellerId = :sellerId AND c.couponId = :couponId")
    Optional<SellerCoupon> findWithLockBySellerIdAndCouponId(
            @Param("sellerId") UUID sellerId, @Param("couponId") UUID couponId);

    @Modifying(flushAutomatically = true)
    @Query(value = """
            UPDATE seller_coupon
               SET reserved_count = reserved_count + 1
             WHERE seller_id = :sellerId
               AND coupon_id = :couponId
               AND active = TRUE
               AND starts_at <= :at
               AND ends_at > :at
               AND max_redemptions IS NOT NULL
               AND reserved_count + redeemed_count < max_redemptions
            """, nativeQuery = true)
    int reserveIfCapacity(@Param("sellerId") UUID sellerId, @Param("couponId") UUID couponId,
                          @Param("at") Instant at);

    @Modifying(flushAutomatically = true)
    @Query(value = """
            UPDATE seller_coupon
               SET reserved_count = reserved_count - 1,
                   redeemed_count = redeemed_count + 1
             WHERE seller_id = :sellerId
               AND coupon_id = :couponId
               AND max_redemptions IS NOT NULL
               AND reserved_count > 0
               AND redeemed_count < max_redemptions
            """, nativeQuery = true)
    int redeemReserved(@Param("sellerId") UUID sellerId, @Param("couponId") UUID couponId);

    @Modifying(flushAutomatically = true)
    @Query(value = """
            UPDATE seller_coupon
               SET reserved_count = reserved_count - 1
             WHERE seller_id = :sellerId
               AND coupon_id = :couponId
               AND max_redemptions IS NOT NULL
               AND reserved_count > 0
            """, nativeQuery = true)
    int releaseReserved(@Param("sellerId") UUID sellerId, @Param("couponId") UUID couponId);
}
