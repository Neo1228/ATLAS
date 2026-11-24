package com.atlas.catalog.service;

import com.atlas.catalog.dto.response.SellerCouponResponse;
import java.time.Instant;
import java.util.UUID;

public interface SellerCouponService {
    SellerCouponResponse create(UUID sellerId, String code, long discountAmountWon,
                                Instant startsAt, Instant endsAt, Long maxRedemptions);

    SellerCouponResponse findActive(UUID sellerId, String code, Instant at);
    SellerCouponResponse find(UUID sellerId, String code);

    SellerCouponResponse deactivate(UUID sellerId, String code);

    void reserveRedemption(UUID sellerId, UUID couponId, UUID orderId, Instant at);
    void redeemRedemption(UUID sellerId, UUID couponId, UUID orderId, Instant at);
    void releaseRedemption(UUID sellerId, UUID couponId, UUID orderId, Instant at);
}
