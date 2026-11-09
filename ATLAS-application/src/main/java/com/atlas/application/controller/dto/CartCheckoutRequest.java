package com.atlas.application.controller.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.util.List;

public record CartCheckoutRequest(List<@NotNull @Valid CheckoutSellerCouponRequest> sellerCoupons) {
}
