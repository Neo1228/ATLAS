package com.atlas.catalog.dto.response;

import java.time.Instant;
import java.util.UUID;

public record SellerCouponResponse(UUID couponId, UUID sellerId, String code,
                                   long discountAmountWon, Instant startsAt, Instant endsAt,
                                   boolean active) {
}
