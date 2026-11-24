package com.atlas.catalog.service;

import com.atlas.catalog.dto.response.CatalogResponse;
import java.time.Instant;
import java.util.UUID;

public interface SellerCouponService {
    CatalogResponse.SellerCoupon create(UUID sellerId, String code, long discountAmountWon,
                                Instant startsAt, Instant endsAt, Long maxRedemptions);

    CatalogResponse.SellerCoupon findActive(UUID sellerId, String code, Instant at);
    CatalogResponse.SellerCoupon find(UUID sellerId, String code);

    CatalogResponse.SellerCoupon deactivate(UUID sellerId, String code);

    void reserveRedemption(UUID sellerId, UUID couponId, UUID orderId, Instant at);
    void redeemRedemption(UUID sellerId, UUID couponId, UUID orderId, Instant at);
    void releaseRedemption(UUID sellerId, UUID couponId, UUID orderId, Instant at);
}
