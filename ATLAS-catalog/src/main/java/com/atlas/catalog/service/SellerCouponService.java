package com.atlas.catalog.service;

import com.atlas.catalog.dto.response.SellerCouponResponse;
import java.time.Instant;
import java.util.UUID;

public interface SellerCouponService {
    SellerCouponResponse create(UUID sellerId, String code, long discountAmountWon,
                                Instant startsAt, Instant endsAt);

    SellerCouponResponse findActive(UUID sellerId, String code, Instant at);

    SellerCouponResponse deactivate(UUID sellerId, String code);
}
