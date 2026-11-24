package com.atlas.application.controller.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import java.util.UUID;

public record CheckoutSellerCouponRequest(
        @NotNull UUID sellerId,
        @NotBlank @Pattern(regexp = "[A-Za-z0-9_-]{3,32}") String code) {
}
