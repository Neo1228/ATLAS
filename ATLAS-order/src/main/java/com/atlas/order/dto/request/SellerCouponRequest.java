package com.atlas.order.dto.request;

import java.util.UUID;

public record SellerCouponRequest(UUID sellerId, String code) {
}
